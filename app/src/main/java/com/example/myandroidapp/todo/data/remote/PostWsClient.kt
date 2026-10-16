package com.example.myandroidapp.todo.data.remote

import android.util.Log
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.todo.data.Location
import com.example.myandroidapp.todo.data.Post
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject


class PostWsClient(private val okHttpClient: OkHttpClient) {
    // null cat timp nu exista o conexiune deschisa (inainte era lateinit -> crash la close/send)
    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isConnected = false

    // Ultimul token primit. Se trimite automat la fiecare (re)conectare, in onOpen,
    // ca serverul sa stie carui utilizator ii trimite evenimentele.
    @Volatile
    private var token: String? = null

    /**
     * Deschide conexiunea. NU mai oprim executorService-ul lui OkHttp dupa conectare:
     * clientul e comun (Api.okHttpClient) si oprirea lui facea imposibila orice reconectare.
     */
    fun openSocket(
        onOpen: () -> Unit,
        onEvent: (postEvent: PostEvent?) -> Unit,
        onClosed: () -> Unit,
        onFailure: () -> Unit
    ): WebSocket {
        Log.d(TAG, "openSocket ${Api.wsUrl}")
        val request = Request.Builder().url(Api.wsUrl).build()
        val socket = okHttpClient.newWebSocket(
            request,
            PostWebSocketListener(
                onOpen = onOpen,
                onEvent = onEvent,
                onClosed = onClosed,
                onFailure = onFailure
            )
        )
        webSocket = socket
        return socket
    }

    /** Inchide socket-ul curent (ex. aplicatia trece in fundal). */
    fun closeSocket() {
        Log.d(TAG, "closeSocket")
        val socket = webSocket
        webSocket = null
        isConnected = false
        socket?.close(1000, "")
    }

    /**
     * Inchide exact socket-ul dat. Daca intre timp s-a deschis unul nou (iesire si
     * revenire rapida in aplicatie), pe cel nou nu il atingem.
     */
    fun closeSocket(socket: WebSocket) {
        if (webSocket === socket) {
            webSocket = null
            isConnected = false
        }
        socket.close(1000, "")
    }

    private fun isCurrent(socket: WebSocket) = webSocket === socket

    fun isWebSocketOpen(): Boolean = isConnected

    /** Trimite un mesaj doar daca socket-ul e deschis. */
    fun send(text: String): Boolean {
        val socket = webSocket
        return if (isConnected && socket != null) socket.send(text) else false
    }

    inner class PostWebSocketListener(
        private val onOpen: () -> Unit,
        private val onEvent: (itemEvent: PostEvent?) -> Unit,
        private val onClosed: () -> Unit,
        private val onFailure: () -> Unit
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "onOpen")
            if (!isCurrent(webSocket)) {
                // un socket vechi s-a deschis dupa ce am cerut inchiderea lui
                webSocket.close(1000, "")
                return
            }
            isConnected = true
            token?.let { sendAuthorization(webSocket, it) }
            onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "onMessage string ${text.take(300)}")
            // Orice mesaj neasteptat e ignorat; inainte o exceptie aici inchidea conexiunea.
            val event = try {
                parseEvent(text)
            } catch (e: Exception) {
                Log.w(TAG, "Mesaj WebSocket ignorat (format necunoscut)", e)
                null
            }
            if (event != null) onEvent(event)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d(TAG, "onMessage bytes $bytes")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "onClosed code: $code reason: $reason")
            if (isCurrent(webSocket)) isConnected = false
            onClosed()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d(TAG, "onFailure throwable: $t")
            if (isCurrent(webSocket)) isConnected = false
            onFailure()
        }
    }

    /**
     * Accepta atat { event, payload: { ...post } } cat si { event, payload: { item: { ...post } } },
     * iar campul de tip poate fi "event" sau "type". Locatia poate fi obiect sau sir "lat,lon".
     */
    private fun parseEvent(text: String): PostEvent? {
        val json = JSONObject(text)
        val event = json.optString("event").ifEmpty { json.optString("type") }
        if (event !in setOf("created", "updated", "deleted")) return null
        val payload = json.optJSONObject("payload") ?: return null
        val p = payload.optJSONObject("item") ?: payload

        val id = p.opt("id")?.toString().orEmpty().ifEmpty { p.opt("_id")?.toString().orEmpty() }
        if (id.isEmpty()) return null

        val location = when (val loc = p.opt("location")) {
            is JSONObject -> Location(loc.optDouble("latitude", 0.0), loc.optDouble("longitude", 0.0))
            is String -> loc.split(",").let {
                if (it.size == 2) Location(it[0].trim().toDoubleOrNull() ?: 0.0, it[1].trim().toDoubleOrNull() ?: 0.0)
                else Location()
            }
            else -> Location()
        }

        val post = Post(
            id = id,
            photo = p.optString("photo"),
            description = p.optString("description"),
            user_id = p.opt("user_id")?.toString().orEmpty(),
            user_profile_photo = p.optString("user_profile_photo"),
            user_last_name = p.optString("user_last_name"),
            user_first_name = p.optString("user_first_name"),
            professional = p.optBoolean("professional", false),
            created_at = p.optString("created_at"),
            updated_at = p.optString("updated_at"),
            isNotSaved = false,
            location = location,
            isPrivate = false
        )
        return PostEvent(event, Payload(post))
    }

    private fun sendAuthorization(socket: WebSocket, token: String) {
        val auth = JSONObject()
            .put("type", "authorization")
            .put("payload", JSONObject().put("token", token))
            .toString()
        Log.d(TAG, "send authorization")
        socket.send(auth)
    }

    /** Pastreaza token-ul si il trimite imediat daca socket-ul e deja deschis. */
    fun authorize(token: String) {
        this.token = token.ifEmpty { null }
        val socket = webSocket
        if (isConnected && socket != null && this.token != null) {
            sendAuthorization(socket, token)
        } else {
            Log.d(TAG, "Token pastrat; va fi trimis la deschiderea socket-ului")
        }
    }
}
