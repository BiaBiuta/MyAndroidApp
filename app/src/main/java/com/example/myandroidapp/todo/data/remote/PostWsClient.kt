package com.example.myandroidapp.todo.data.remote

import android.util.Log
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.todo.data.Location
import com.example.myandroidapp.todo.data.Post
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject


class PostWsClient(private val okHttpClient: OkHttpClient) {
    lateinit var webSocket: WebSocket
    private var isConnected = false // Flag pentru starea conexiunii

    suspend fun openSocket(
        onEvent: (postEvent: PostEvent?) -> Unit,
        onClosed: () -> Unit,
        onFailure: () -> Unit
    ) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "openSocket")
            val request = Request.Builder().url(Api.wsUrl).build()
            webSocket = okHttpClient.newWebSocket(
                request,
                PostWebSocketListener(
                    onEvent = onEvent,
                    onClosed = onClosed,
                    onFailure = onFailure
                )
            )
            okHttpClient.dispatcher.executorService.shutdown()
        }
    }

    fun closeSocket() {
        Log.d(TAG, "closeSocket")
        isConnected = false
        webSocket.close(1000, "")
    }

    fun isWebSocketOpen(): Boolean {
        return isConnected
    }

    inner class PostWebSocketListener(
        private val onEvent: (itemEvent: PostEvent?) -> Unit,
        private val onClosed: () -> Unit,
        private val onFailure: () -> Unit
    ) : WebSocketListener() {
        private val moshi = Moshi.Builder().build()
        private val itemEventJsonAdapter: JsonAdapter<PostEvent> =
            moshi.adapter(PostEvent::class.java)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "onOpen")
            isConnected = true // Actualizare stare conexiune
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "onMessage string $text")
            val jsonObject = JSONObject(text)

            // Extrage valoarea `event`
            val event = jsonObject.getString("event")

            // Extrage obiectul `payload`
            val payload = jsonObject.getJSONObject("payload")
           // val jsonObject = JSONObject(text)

            // Extrage valorile câmpurilor
            val createdAt = payload.getString("created_at")
            val description = payload.getString("description")
            val id = payload.getInt("id")
            val isNotSaved = payload.getBoolean("isNotSaved")

            // Extrage obiectul `location`
            val location = payload.getJSONObject("location")
            val latitude = location.getDouble("latitude")
            val longitude = location.getDouble("longitude")

            val photo = payload.getString("photo")
            //val itemEvent = itemEventJsonAdapter.fromJson(text)
            val itemEvent=PostEvent(event, Payload(Post(id.toString(),photo,description,"","","","",false,createdAt, "" ,isNotSaved,
                Location(latitude,longitude))))

            Log.d(TAG,itemEvent.toString())
            onEvent(itemEvent)

        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d(TAG, "onMessage bytes $bytes")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {}

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "onClosed code: $code reason: $reason")
            isConnected = false // Actualizare stare conexiune
            onClosed()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d(TAG, "onFailure throwable: $t")
            isConnected = false // Actualizare stare conexiune
            onFailure()
        }
    }

    fun authorize(token: String) {
        val auth = """
            {
              "type":"authorization",
              "payload":{
                "token": "$token"
              }
            }
        """.trimIndent()
        Log.d(TAG, "auth $auth")
        if (isConnected) { // Verificare conexiune înainte de trimitere
            webSocket.send(auth)
        } else {
            Log.w(TAG, "Cannot authorize, WebSocket is not open")
        }
    }
}
