package com.example.myandroidapp.todo.data

import android.util.Log
import com.example.myandroidapp.core.Result
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.todo.data.remote.Payload
import com.example.myandroidapp.todo.data.remote.PostEvent
import com.example.myandroidapp.todo.data.remote.PostService
import com.example.myandroidapp.todo.data.remote.PostWsClient
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import   kotlinx. coroutines. channels.SendChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlin.collections.List

//post Service is an Rest item with 4 endpoints
class PostRepository(
    private val postService: PostService,
    private val postWsClient: PostWsClient,
    ) {
    private var posts: kotlin.collections.List<Post> = kotlin.collections.listOf()

    private fun getBearerToken() = "Bearer ${Api.tokenInterceptor.token}"

    private var postsFlow: MutableSharedFlow<Result<List<Post>>> = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val itemStream: Flow<Result<List<Post>>> = postsFlow

    init {
        Log.d(TAG, "init")
    }
    suspend fun parseLocation(locationString: String): Location {
        val coordinates = locationString.split(",")
        return if (coordinates.size == 2) {
            Location(
                latitude = coordinates[0].toDoubleOrNull() ?: 0.0,
                longitude = coordinates[1].toDoubleOrNull() ?: 0.0
            )
        } else {
            Location()
        }
    }

    suspend fun refresh() {

        Log.d(TAG, "refresh started")
        //Log.d(TAG,getBearerToken())
        try {
            posts = postService.find(authorization = getBearerToken())
            Log.d(TAG, "refresh succeeded")
            postsFlow.emit(Result.Success(posts))
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
            postsFlow.emit(Result.Error(e))
        }
    }
    suspend fun openWsClient() {
        Log.d(TAG, "openWsClient")
        withContext(Dispatchers.IO) {
            getPostEvents().collect {
                Log.d(TAG, "Item event collected $it")
                if (it is Result.Success) {
                    val itemEvent = it.data;
                    when (itemEvent.event) {
                        "created" -> handleItemCreated(itemEvent.payload.item)
                        "updated" -> handleItemUpdated(itemEvent.payload.item)
                        "deleted" -> handleItemDeleted(itemEvent.payload.item)
                    }
                }
            }
        }
    }
    suspend fun closeWsClient() {
        Log.d(TAG, "closeWsClient")
        withContext(Dispatchers.IO) {
            postWsClient.closeSocket()
        }
    }
    suspend fun getPostEvents(): Flow<Result<PostEvent>> = callbackFlow {
        Log.d(TAG, "getItemEvents started")
        postWsClient.openSocket(
            onEvent = {
                Log.d(TAG, "onEvent $it")
                if (it != null) {
                    Log.d(TAG, "onEvent trySend $it")
                    trySend(Result.Success(it))
                }
            },
            onClosed = { close() },
            onFailure = { close() });
        awaitClose { postWsClient.closeSocket() }
    }
    suspend fun update(item: Post): Post {
        Log.d(TAG, "update $item...")
        val updatedItem =
            postService.update(itemId = item.id, item = item, authorization = getBearerToken())
        Log.d(TAG, "update $item succeeded")
        handleItemUpdated(updatedItem)
        return updatedItem
    }

    suspend fun save(item: Post): Post {
        Log.d(TAG, "save $item...")
        val createdItem = postService.create(item = item, authorization = getBearerToken())
        Log.d(TAG, "save $item succeeded")
        handleItemCreated(createdItem)
        val moshi = Moshi.Builder().build()
        val jsonAdapter = moshi.adapter(PostEvent::class.java)
        val postEvent = PostEvent(
            event = "created",
            payload = Payload(createdItem)
        )
        val eventJson = jsonAdapter.toJson(postEvent)

        // Trimite mesajul JSON prin WebSocket
        try {

            postWsClient.webSocket.send(eventJson)
            Log.d(TAG, "WebSocket event sent: $eventJson")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send WebSocket event", e)
        }
        // Trimite evenimentul către WebSocket
        Log.d(TAG, "WebSocket event sent for $item")
        return createdItem
    }

    private suspend fun handleItemDeleted(item: Post) {
        Log.d(TAG, "handleItemDeleted - todo $item")
    }

    private suspend fun handleItemUpdated(post: Post) {
        Log.d(TAG, "handleItemUpdated...")
        //postDao.update(item)
        posts = posts.map { if (it.id == post.id) post else it }


    }

    private suspend fun handleItemCreated(post: Post) {
        Log.d(TAG, "handleItemCreated...")
        Log.d(TAG,post.toString())
        posts = listOf(post) + posts
        //Log.d(posts.toString(),"posts")
        postsFlow.emit(Result.Success(posts))
        //postDao.insert(item)
    }

    suspend fun deleteAll() {
        //postDao.deleteAll()
    }

    fun setToken(token: String) {
        postWsClient.authorize(token)
    }
//    suspend fun deleteItem(item: Post) {
//        postDao.(item.lat, item.lon);
//    }
}