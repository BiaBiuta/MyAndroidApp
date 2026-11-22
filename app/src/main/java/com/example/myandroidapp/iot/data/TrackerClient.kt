package com.example.myandroidapp.iot.data

import com.example.myandroidapp.iot.crypto.PairingCrypto
import com.example.myandroidapp.iot.update.UpdateManifest
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Ce spune trackerul despre el inainte de asociere (neautentificat: doar pentru afisare). */
data class TrackerInfo(
    val deviceId: String = "",
    val name: String = "",
    val model: String = "",
    val firmware: String = "",
    val paired: Boolean = false,
    val pairingMode: Boolean = false
)

data class PairStartResponse(val sessionId: String = "", val devicePublicKey: String = "", val commitment: String = "")
data class PairNonceResponse(val deviceNonce: String = "")
data class PairConfirmResponse(
    val deviceId: String = "",
    val model: String = "",
    val firmware: String = "",
    val identityPublicKey: String = "",
    val mac: String = ""
)

data class TrackerLastReport(val place: String = "", val at: String = "")
data class TrackerState(
    val deviceId: String = "",
    val model: String = "",
    val firmware: String = "",
    val config: Map<String, Any>? = null,
    val paired: Boolean = false,
    val revoked: Boolean = false,
    val lastReport: TrackerLastReport? = null
)

/** Trackerul a raspuns cu o eroare (mesajul lui, in romana). */
class TrackerException(val status: Int, override val message: String) : Exception(message)

/** Trackerul nu poate fi contactat (oprit, adresa gresita, alta retea). */
class TrackerUnreachableException(host: String, cause: Throwable) :
    IOException("Trackerul nu răspunde la $host. Verifică dacă rulează (node device/tracker.js) și adresa.", cause)

/**
 * Comunicarea directa telefon <-> tracker, in reteaua locala (emulator: 10.0.2.2:4000).
 * Client OkHttp SEPARAT de cel al serverului: token-ul contului nu ajunge niciodata la tracker.
 * Dupa asociere, fiecare comanda e autentificata cu HMAC-ul cheii de asociere (+ ora si nonce).
 */
class TrackerClient(private val gson: Gson = Gson()) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun info(host: String): TrackerInfo = call(host, "GET", "/info", null, null)

    suspend fun pairStart(host: String, appPublicKey: ByteArray): PairStartResponse =
        call(host, "POST", "/pair/start", mapOf("appPublicKey" to PairingCrypto.b64(appPublicKey)), null)

    suspend fun pairNonce(host: String, sessionId: String, appNonce: ByteArray): PairNonceResponse =
        call(host, "POST", "/pair/nonce", mapOf("sessionId" to sessionId, "appNonce" to PairingCrypto.b64(appNonce)), null)

    suspend fun pairConfirm(host: String, sessionId: String, mac: ByteArray): PairConfirmResponse =
        call(host, "POST", "/pair/confirm", mapOf("sessionId" to sessionId, "mac" to PairingCrypto.b64(mac)), null)

    suspend fun state(host: String, key: ByteArray): TrackerState = call(host, "GET", "/state", null, key)

    suspend fun install(host: String, key: ByteArray, manifest: UpdateManifest): TrackerState =
        call(host, "POST", "/update", mapOf("manifest" to manifest), key)

    suspend fun unpair(host: String, key: ByteArray) {
        call<Map<String, Any>>(host, "POST", "/unpair", null, key)
    }

    private suspend inline fun <reified T> call(host: String, method: String, path: String, body: Any?, key: ByteArray?): T =
        withContext(Dispatchers.IO) {
            val bytes = body?.let { gson.toJson(it).toByteArray(Charsets.UTF_8) } ?: ByteArray(0)
            val request = Request.Builder()
                .url("http://$host$path")
                .apply {
                    if (method == "GET") get() else post(bytes.toRequestBody(json))
                    key?.let { PairingCrypto.commandHeaders(it, method, path, bytes).forEach { (k, v) -> header(k, v) } }
                }
                .build()
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw TrackerUnreachableException(host, e)
            }
            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    val message = try {
                        JsonParser().parse(text).asJsonObject.get("message")?.asString
                    } catch (e: Exception) {
                        null
                    }
                    throw TrackerException(it.code, message ?: "Trackerul a răspuns cu eroarea ${it.code}.")
                }
                gson.fromJson(text, T::class.java)
            }
        }
}
