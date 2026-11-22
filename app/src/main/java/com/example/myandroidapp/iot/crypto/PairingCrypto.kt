package com.example.myandroidapp.iot.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Criptografia asocierii cu trackerul WanderLog (aceleasi formule ca server/src/iot.js).
 *
 * Asocierea (cod out-of-band, nu o conexiune deschisa):
 *  1. telefonul trimite cheia lui ECDH (P-256); trackerul raspunde cu cheia lui si un ANGAJAMENT
 *     la nonce-ul sau: commitment = HMAC(nonceTracker, "WL-COMMIT" | cheieTelefon | cheieTracker);
 *  2. telefonul trimite nonce-ul lui; trackerul isi dezvaluie nonce-ul si AFISEAZA un cod de 6 cifre
 *     calculat din transcript = SHA-256("WL-PAIR-v1" | chei | nonce-uri);
 *  3. telefonul verifica angajamentul, calculeaza si el codul, iar utilizatorul tasteaza codul vazut
 *     pe tracker. Daca cineva s-a interpus (MITM), transcripturile difera si codurile nu se potrivesc;
 *     angajamentul il impiedica sa "potriveasca" codul prin incercari;
 *  4. cheia de asociere K = HKDF-SHA256(secret ECDH, sare = transcript, "WL-PAIRING-KEY");
 *     ambele parti dovedesc ca au aceeasi K (HMAC de confirmare), iar trackerul trimite, autentificata
 *     cu K, cheia lui publica de identitate (cea inregistrata apoi pe server).
 */
object PairingCrypto {
    private const val PAIR_LABEL = "WL-PAIR-v1"
    private const val COMMIT_LABEL = "WL-COMMIT"
    private const val KEY_INFO = "WL-PAIRING-KEY"
    private const val APP_CONFIRM = "WL-APP-CONFIRM"
    private const val DEVICE_CONFIRM = "WL-DEVICE-CONFIRM"

    private val random = SecureRandom()

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), random)
        generateKeyPair()
    }

    /** Cheie publica EC din SPKI (DER), formatul folosit si de tracker / server. */
    fun publicKeyFromDer(der: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach(::update)
        digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach(::update)
        doFinal()
    }

    fun commitment(deviceNonce: ByteArray, appPub: ByteArray, devicePub: ByteArray): ByteArray =
        hmac(deviceNonce, COMMIT_LABEL.utf8(), appPub, devicePub)

    fun transcript(appPub: ByteArray, devicePub: ByteArray, appNonce: ByteArray, deviceNonce: ByteArray): ByteArray =
        sha256(PAIR_LABEL.utf8(), appPub, devicePub, appNonce, deviceNonce)

    /** Codul de 6 cifre: primii 4 octeti ai transcriptului (numar fara semn) modulo 1.000.000. */
    fun pairingCode(transcript: ByteArray): String {
        val value = ((transcript[0].toLong() and 0xff) shl 24) or
                ((transcript[1].toLong() and 0xff) shl 16) or
                ((transcript[2].toLong() and 0xff) shl 8) or
                (transcript[3].toLong() and 0xff)
        return (value % 1_000_000).toString().padStart(6, '0')
    }

    /** K = HKDF-SHA256(ECDH(cheiaMea, cheiaLui), sare = transcript, info = "WL-PAIRING-KEY"), 32 octeti. */
    fun deriveKey(myPrivate: PrivateKey, peerPublic: PublicKey, transcript: ByteArray): ByteArray {
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(myPrivate)
            doPhase(peerPublic, true)
            generateSecret()
        }
        // HKDF (RFC 5869): extract + expand (un singur bloc, 32 octeti)
        val prk = hmac(transcript, shared)
        return hmac(prk, KEY_INFO.utf8(), byteArrayOf(1))
    }

    fun appConfirmMac(key: ByteArray, transcript: ByteArray, code: String): ByteArray =
        hmac(key, APP_CONFIRM.utf8(), transcript, code.utf8())

    fun deviceConfirmMac(key: ByteArray, transcript: ByteArray, identityPub: ByteArray): ByteArray =
        hmac(key, DEVICE_CONFIRM.utf8(), transcript, identityPub)

    /** Id-ul trackerului = amprenta cheii lui de identitate (ca pe server). */
    fun deviceIdFor(identityPub: ByteArray): String =
        "WLT-" + sha256(identityPub).copyOfRange(0, 5).toHex().uppercase()

    /** Comparatie in timp constant (nu dezvaluie, prin durata, cate cifre/octeti se potrivesc). */
    fun sameBytes(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // ---------- Comenzi catre tracker, autentificate cu K ----------

    fun canonicalRequest(method: String, path: String, time: String, nonce: String, body: ByteArray): String =
        "${method.uppercase()}\n$path\n$time\n$nonce\n${sha256(body).toHex()}"

    /** Antetele unei comenzi: ora, nonce (anti-reluare) si HMAC-ul cu cheia de asociere. */
    fun commandHeaders(key: ByteArray, method: String, path: String, body: ByteArray): Map<String, String> {
        val time = System.currentTimeMillis().toString()
        val nonce = Base64.encodeToString(randomBytes(12), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val mac = hmac(key, canonicalRequest(method, path, time, nonce, body).utf8())
        return mapOf("X-WL-Time" to time, "X-WL-Nonce" to nonce, "X-WL-Mac" to b64(mac))
    }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unb64(text: String): ByteArray = Base64.decode(text, Base64.DEFAULT)

    private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
