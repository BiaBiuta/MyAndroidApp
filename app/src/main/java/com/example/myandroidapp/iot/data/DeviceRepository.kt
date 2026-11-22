package com.example.myandroidapp.iot.data

import android.util.Log
import com.example.myandroidapp.core.data.remote.ServerException
import com.example.myandroidapp.core.data.remote.apiCall
import com.example.myandroidapp.core.data.remote.toUserMessage
import com.example.myandroidapp.core.security.SecureStore
import com.example.myandroidapp.iot.crypto.PairingCrypto
import com.example.myandroidapp.iot.data.local.DeviceStatus
import com.example.myandroidapp.iot.data.local.PairedDeviceDao
import com.example.myandroidapp.iot.data.local.PairedDeviceEntity
import com.example.myandroidapp.iot.data.remote.DeviceService
import com.example.myandroidapp.iot.data.remote.DeviceVisit
import com.example.myandroidapp.iot.data.remote.RegisterDeviceRequest
import com.example.myandroidapp.iot.update.UpdateCheck
import com.example.myandroidapp.iot.update.UpdateManifest
import com.example.myandroidapp.iot.update.UpdateVerifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Asocierea a esuat; fatal = sesiunea s-a incheiat si trebuie reluata de la inceput. */
class PairingException(message: String, val fatal: Boolean) : Exception(message)

/** Aplicatia a refuzat sa trimita actualizarea la tracker (semnatura / model / versiune). */
class UpdateRejectedException(message: String) : Exception(message)

/**
 * Asocierea inceputa: trackerul a afisat codul. Pastram doar ce trebuie pentru confirmare
 * (cheia privata ECDH a telefonului nu mai e necesara si nu se pastreaza).
 */
class PendingPairing internal constructor(
    val host: String,
    val info: TrackerInfo,
    internal val sessionId: String,
    internal val transcript: ByteArray,
    internal val key: ByteArray,
    internal val expectedCode: String
) {
    var wrongAttempts: Int = 0
        internal set
}

/**
 * Trackerele contului curent. Ascunde de UI cele trei surse: trackerul (retea locala),
 * serverul (inregistrare, revocare, locuri, actualizari) si baza de date locala (Room).
 */
class DeviceRepository(
    private val service: DeviceService,
    private val dao: PairedDeviceDao,
    private val tracker: TrackerClient,
    private val ownerKeys: Flow<String>,
    private val currentOwnerKey: suspend () -> String
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val devices: Flow<List<PairedDeviceEntity>> = ownerKeys.flatMapLatest { owner ->
        if (owner.isBlank()) flowOf(emptyList()) else dao.observeForOwner(owner)
    }

    // ---------- Asocierea ----------

    suspend fun info(host: String): TrackerInfo = tracker.info(host)

    /**
     * Pasii 1-2: schimbul de chei si de nonce-uri. Dupa asta trackerul AFISEAZA codul.
     * Verificam angajamentul trackerului: daca nu se potriveste, cineva a modificat mesajele.
     */
    suspend fun beginPairing(host: String): PendingPairing {
        val info = tracker.info(host)
        if (info.paired) throw PairingException("Trackerul este deja asociat. Resetează-l (r în consola lui) ca să-l asociezi din nou.", true)
        val keys = PairingCrypto.newKeyPair()
        val appPub = keys.public.encoded // SPKI (DER)
        val start = tracker.pairStart(host, appPub)
        val devicePub = PairingCrypto.unb64(start.devicePublicKey)
        val appNonce = PairingCrypto.randomBytes(16)
        val reveal = tracker.pairNonce(host, start.sessionId, appNonce)
        val deviceNonce = PairingCrypto.unb64(reveal.deviceNonce)

        val commitmentOk = PairingCrypto.sameBytes(
            PairingCrypto.commitment(deviceNonce, appPub, devicePub),
            PairingCrypto.unb64(start.commitment)
        )
        if (!commitmentOk) {
            throw PairingException("Trackerul nu a respectat angajamentul de asociere (posibilă interceptare). Asocierea a fost oprită.", true)
        }
        val transcript = PairingCrypto.transcript(appPub, devicePub, appNonce, deviceNonce)
        val key = PairingCrypto.deriveKey(keys.private, PairingCrypto.publicKeyFromDer(devicePub), transcript)
        return PendingPairing(host, info, start.sessionId, transcript, key, PairingCrypto.pairingCode(transcript))
    }

    /**
     * Pasul 3: utilizatorul a tastat codul de pe tracker. Daca nu e identic cu cel calculat aici,
     * nu trimitem nimic (o interceptare ar produce alt cod). Daca e corect: confirmarea reciproca,
     * cheia de identitate a trackerului (autentificata cu K), inregistrarea pe server, salvarea locala.
     */
    suspend fun completePairing(pending: PendingPairing, typedCode: String, name: String): PairedDeviceEntity {
        val code = typedCode.filter(Char::isDigit)
        val matches = code.length == 6 &&
                PairingCrypto.sameBytes(code.toByteArray(), pending.expectedCode.toByteArray())
        if (!matches) {
            pending.wrongAttempts += 1
            if (pending.wrongAttempts >= MAX_CODE_ATTEMPTS) {
                throw PairingException("Codul greșit de $MAX_CODE_ATTEMPTS ori. Asocierea a fost anulată; pornește din nou modul de asociere pe tracker.", true)
            }
            throw PairingException(
                "Codul nu se potrivește cu cel de pe tracker. Verifică cifrele (mai ai ${MAX_CODE_ATTEMPTS - pending.wrongAttempts} încercări). " +
                        "Dacă ești sigur că l-ai tastat corect, cineva poate intercepta conexiunea: nu continua.",
                false
            )
        }

        val confirm = tracker.pairConfirm(pending.host, pending.sessionId, PairingCrypto.appConfirmMac(pending.key, pending.transcript, code))
        val identity = PairingCrypto.unb64(confirm.identityPublicKey)
        val deviceProved = PairingCrypto.sameBytes(
            PairingCrypto.deviceConfirmMac(pending.key, pending.transcript, identity),
            PairingCrypto.unb64(confirm.mac)
        )
        if (!deviceProved || PairingCrypto.deviceIdFor(identity) != confirm.deviceId) {
            throw PairingException("Trackerul nu a putut dovedi că are cheia de asociere. Asocierea a fost oprită.", true)
        }

        val owner = currentOwnerKey()
        val deviceName = name.trim().ifBlank { "WanderLog Tracker" }.take(60)
        try {
            // cheia publica de identitate ajunge pe server: de acum serverul accepta DOAR cererile semnate cu ea
            apiCall {
                service.register(
                    RegisterDeviceRequest(confirm.deviceId, deviceName, confirm.model, confirm.firmware, PairingCrypto.b64(identity))
                )
            }
        } catch (e: Exception) {
            // fara inregistrare pe server, asocierea nu are sens: o anulam si pe tracker
            runCatching { tracker.unpair(pending.host, pending.key) }
            throw e
        }

        val device = PairedDeviceEntity(
            id = confirm.deviceId,
            ownerKey = owner,
            name = deviceName,
            model = confirm.model,
            firmware = confirm.firmware,
            host = pending.host,
            identityPublicKey = PairingCrypto.b64(identity),
            pairingKey = SecureStore.encrypt(PairingCrypto.b64(pending.key)),
            status = DeviceStatus.ACTIVE,
            pairedAt = System.currentTimeMillis()
        )
        dao.upsert(device)
        return device
    }

    // ---------- Dupa asociere ----------

    /** Starea de pe server (ex. revocat de pe alt telefon, firmware) -> baza locala. null = ok. */
    suspend fun refreshFromServer(): String? = try {
        val remote = apiCall { service.devices() }.associateBy { it.id }
        val owner = currentOwnerKey()
        for (device in remote.values) {
            val local = dao.find(device.id, owner) ?: continue
            if (device.status == DeviceStatus.REVOKED && local.status != DeviceStatus.REVOKED) {
                dao.setStatus(local.id, DeviceStatus.REVOKED)
            }
            if (device.firmware.isNotBlank() && device.firmware != local.firmware) dao.setFirmware(local.id, device.firmware)
        }
        null
    } catch (e: Exception) {
        Log.w(TAG, "refresh devices failed", e)
        e.message
    }

    /** Starea raportata chiar de tracker (comanda autentificata cu K). */
    suspend fun trackerState(device: PairedDeviceEntity): TrackerState =
        tracker.state(device.host, keyOf(device))

    suspend fun visits(device: PairedDeviceEntity): List<DeviceVisit> = apiCall { service.visits(device.id) }

    /**
     * Tracker pierdut / furat: serverul nu-i mai accepta cererile (cheia lui e revocata), iar
     * cheia de asociere se sterge de pe telefon. Nu avem nevoie de tracker pentru asta.
     */
    suspend fun revoke(device: PairedDeviceEntity) {
        apiCall { service.revoke(device.id) }
        dao.setStatus(device.id, DeviceStatus.REVOKED)
    }

    /** Scoate din lista locala un tracker deja revocat. */
    suspend fun forget(device: PairedDeviceEntity) {
        if (device.status != DeviceStatus.REVOKED) {
            throw IllegalStateException("Revocă întâi trackerul, apoi îl poți scoate din listă.")
        }
        dao.delete(device.id, device.ownerKey)
    }

    /** Actualizarile publicate + rezultatul verificarii semnaturii pentru acest tracker. */
    suspend fun updates(device: PairedDeviceEntity): List<Pair<UpdateManifest, UpdateCheck>> =
        apiCall { service.updates() }.map { it to UpdateVerifier.verify(it, device.model, device.firmware) }

    /**
     * Instalarea unei actualizari: verificam semnatura producatorului AICI, inainte de a trimite
     * ceva la tracker. Daca verificarea esueaza, actualizarea nu pleaca de pe telefon.
     */
    suspend fun install(device: PairedDeviceEntity, manifest: UpdateManifest): String {
        if (device.status != DeviceStatus.ACTIVE) throw IllegalStateException("Trackerul a fost revocat.")
        when (val check = UpdateVerifier.verify(manifest, device.model, device.firmware)) {
            is UpdateCheck.Rejected -> throw UpdateRejectedException("Actualizarea a fost refuzată și NU a fost trimisă la tracker. ${check.reason}")
            is UpdateCheck.Valid -> {
                val state = tracker.install(device.host, keyOf(device), manifest)
                dao.setFirmware(device.id, state.firmware)
                try {
                    apiCall { service.setFirmware(device.id, mapOf("version" to state.firmware)) }
                } catch (e: Exception) {
                    // trackerul e actualizat; serverul afla versiunea la urmatoarea sincronizare
                    Log.w(TAG, "firmware report failed", e)
                }
                return "Semnătura a fost verificată. Versiunea ${check.update.version} a fost instalată pe tracker."
            }
        }
    }

    private fun keyOf(device: PairedDeviceEntity): ByteArray {
        if (device.status != DeviceStatus.ACTIVE || device.pairingKey.isBlank()) {
            throw IllegalStateException("Trackerul a fost revocat: telefonul nu mai are cheia de asociere.")
        }
        return PairingCrypto.unb64(SecureStore.decrypt(device.pairingKey))
    }

    companion object {
        private const val TAG = "DeviceRepository"
        const val MAX_CODE_ATTEMPTS = 3
        const val DEFAULT_HOST = "10.0.2.2:4000"
    }
}

/** Mesaj prietenos pentru erorile din ecranul de trackere. */
fun Throwable.deviceMessage(fallback: String): String = when (this) {
    is PairingException, is UpdateRejectedException, is TrackerException, is IllegalStateException -> message ?: fallback
    is TrackerUnreachableException -> message ?: fallback
    is ServerException -> message
    else -> toUserMessage(fallback)
}
