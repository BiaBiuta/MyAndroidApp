package com.example.myandroidapp.iot.update

import com.example.myandroidapp.iot.crypto.PairingCrypto
import com.google.gson.JsonParser
import java.security.Signature

///** O actualizare de configurare / firmware, asa cum o publica producatorul (server/updates/*.json). */
data class UpdateManifest(
    val id: String = "",
    val keyId: String = "",
    // textul EXACT semnat (JSON); se verifica semnatura peste acesti octeti, nu peste o copie refacuta
    val payload: String = "",
    // ECDSA P-256 / SHA-256, DER, Base64
    val signature: String = ""
)

/** Continutul unei actualizari, citit DOAR dupa ce semnatura a fost verificata. */
data class UpdateInfo(
    val model: String,
    val version: String,
    val notes: String,
    val releasedAt: String,
    val config: Map<String, String>
)

sealed class UpdateCheck {
    data class Valid(val update: UpdateInfo) : UpdateCheck()
    /** previewVersion: versiunea declarata (necreditata), doar pentru afisare. */
    data class Rejected(val reason: String, val previewVersion: String?) : UpdateCheck()
}

/**
 * Verificarea actualizarilor inainte de a le trimite la tracker: aplicatia are cheia PUBLICA a
 * producatorului (incorporata aici) si refuza orice actualizare a carei semnatura nu se verifica,
 * care e pentru alt model sau care ar duce la o versiune mai veche (rollback).
 * Serverul doar distribuie fisierele: nu le poate semna, deci nici un server compromis nu poate
 * impinge o actualizare falsa. Trackerul verifica si el semnatura (aparare in adancime).
 */
object UpdateVerifier {
    /** Cheia publica a producatorului (server/device/vendor-public.pem), SPKI Base64. */
    const val VENDOR_PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAET4wuGpRqBHqbalAirUW6vtYD21Xw/GpCpV31+xyTqt3WCroQGD49yGxRZHsZDTYsgv0f+glpRgNoYp/61rElPg=="

    private val vendorKey by lazy { PairingCrypto.publicKeyFromDer(PairingCrypto.unb64(VENDOR_PUBLIC_KEY)) }

    fun verify(manifest: UpdateManifest, model: String, currentVersion: String): UpdateCheck {
        val preview = declaredVersion(manifest.payload)
        val signatureOk = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(vendorKey)
                update(manifest.payload.toByteArray(Charsets.UTF_8))
                verify(PairingCrypto.unb64(manifest.signature))
            }
        } catch (e: Exception) {
            false
        }
        if (!signatureOk) {
            return UpdateCheck.Rejected(
                "Semnătura producătorului nu este validă: actualizarea a fost modificată sau nu provine de la producător.",
                preview
            )
        }
        val update = try {
            parse(manifest.payload)
        } catch (e: Exception) {
            return UpdateCheck.Rejected("Conținutul actualizării nu poate fi citit.", preview)
        }
        if (update.model != model) {
            return UpdateCheck.Rejected("Actualizarea este pentru alt model (${update.model}).", update.version)
        }
        if (compareVersions(update.version, currentVersion) <= 0) {
            return UpdateCheck.Rejected(
                "Versiunea ${update.version} nu este mai nouă decât cea instalată (${currentVersion}).",
                update.version
            )
        }
        return UpdateCheck.Valid(update)
    }

    private fun parse(payload: String): UpdateInfo {
        val json = JsonParser().parse(payload).asJsonObject
        val config = json.getAsJsonObject("config")?.entrySet()
            ?.associate { (key, value) -> key to value.asString }
            .orEmpty()
        return UpdateInfo(
            model = json.get("model").asString,
            version = json.get("version").asString,
            notes = json.get("notes")?.asString.orEmpty(),
            releasedAt = json.get("releasedAt")?.asString.orEmpty(),
            config = config
        )
    }

    private fun declaredVersion(payload: String): String? = try {
        JsonParser().parse(payload).asJsonObject.get("version")?.asString
    } catch (e: Exception) {
        null
    }

    /** "1.2.10" > "1.2.9" */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
