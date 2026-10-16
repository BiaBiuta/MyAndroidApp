package com.example.myandroidapp.todo.data

import android.util.Log
import com.example.myandroidapp.core.Result
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.todo.data.remote.Payload
import com.example.myandroidapp.todo.data.remote.PostEvent
import com.example.myandroidapp.todo.data.remote.PostService
import com.example.myandroidapp.todo.data.remote.PostWsClient
import com.example.myapp.todo.data.local.ItemDao
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val itemDao: ItemDao
) {
    private var posts: kotlin.collections.List<Post> = kotlin.collections.listOf()

    private fun getBearerToken() = "Bearer ${Api.tokenInterceptor.token}"

    private var postsFlow: MutableSharedFlow<Result<List<Post>>> = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Doar postarile publice (lista principala)
    val itemStream by lazy { itemDao.getAll() }

    // Doar postarile private ale proprietarului dat (sectiunea privata)
    fun privateItemStream(ownerKey: String): Flow<List<Post>> = itemDao.getPrivate(ownerKey)

    /** Postare privata: se salveaza doar local, nu ajunge niciodata pe server. */
    suspend fun savePrivate(post: Post): Post {
        val privatePost = post.copy(
            id = post.id.ifEmpty { "private-" + java.util.UUID.randomUUID().toString() },
            isPrivate = true,
            isNotSaved = false
        )
        itemDao.insert(privatePost)
        return privatePost
    }

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
            // inlocuim doar postarile publice deja sincronizate; cele private si cele
            // create offline (inca netrimise) raman
            itemDao.deleteAllPublicSynced()
            Log.d(TAG, "refresh succeeded")
            itemDao.insert(posts.map { it.copy(isPrivate = false, isNotSaved = false) })
//            postsFlow.emit(Result.Success(posts))
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
            postsFlow.emit(Result.Error(e))
        }
    }


    // Fiecare apel openWsClient primeste o "generatie". closeWsClient o creste, deci
    // bucla de reconectare veche se opreste si nu ramane socket deschis in fundal.
    @Volatile
    private var wsGeneration = 0

    /**
     * Tine socket-ul deschis cat timp aplicatia e in prim-plan (apelat din onResume).
     * Daca serverul nu raspunde sau conexiunea cade, reincearca automat (2s, 4s, ... max 30s).
     * La fiecare (re)conectare face si un refresh, ca sa prinda postarile aparute intre timp.
     */
    suspend fun openWsClient() {
        val generation = ++wsGeneration
        Log.d(TAG, "openWsClient #$generation")
        withContext(Dispatchers.IO) {
            var backoffMs = 2_000L
            while (isActive && generation == wsGeneration) {
                var opened = false
                getPostEvents(onOpen = { opened = true }).collect {
                    Log.d(TAG, "Item event collected $it")
                    if (it is Result.Success) {
                        val itemEvent = it.data
                        when (itemEvent.event) {
                            "created" -> handleItemCreated(itemEvent.payload.item)
                            "updated" -> handleItemUpdated(itemEvent.payload.item)
                            "deleted" -> handleItemDeleted(itemEvent.payload.item)
                        }
                    }
                }
                // socket-ul s-a inchis sau nu s-a putut conecta
                if (generation != wsGeneration) break
                if (opened) backoffMs = 2_000L
                Log.d(TAG, "WebSocket inchis, reconectare in ${backoffMs}ms")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    suspend fun closeWsClient() {
        Log.d(TAG, "closeWsClient")
        wsGeneration++
        withContext(Dispatchers.IO) {
            postWsClient.closeSocket()
        }
    }

    fun getPostEvents(onOpen: () -> Unit = {}): Flow<Result<PostEvent>> = callbackFlow {
        Log.d(TAG, "getItemEvents started")
        val socket = postWsClient.openSocket(
            onOpen = {
                onOpen()
                // trimitem ce s-a salvat offline, apoi luam ce s-a postat cat timp nu eram conectati
                launch {
                    syncUnsynced()
                    refresh()
                }
            },
            onEvent = {
                if (it != null) {
                    Log.d(TAG, "onEvent ${it.event} ${it.payload.item.id}")
                    trySend(Result.Success(it))
                }
            },
            onClosed = { close() },
            onFailure = { close() })
        awaitClose { postWsClient.closeSocket(socket) }
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

        // Anuntam si prin WebSocket (daca e conectat). Daca nu e, postarea e deja in
        // baza locala, deci apare oricum in lista.
        try {
            val sent = postWsClient.send(eventJson)
            Log.d(TAG, "WebSocket event created sent=$sent")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send WebSocket event", e)
        }
        return createdItem
    }

    private suspend fun handleItemDeleted(item: Post) {
        Log.d(TAG, "handleItemDeleted ${item.id}")
        itemDao.deleteById(item.id)
    }

    private suspend fun handleItemUpdated(post: Post) {
        Log.d(TAG, "handleItemUpdated ${post.id}")
        // scriem in baza locala -> lista (Flow din Room) se actualizeaza singura
        itemDao.insert(post.copy(isPrivate = false, isNotSaved = false))
    }

    /** Dupa ce o postare creata offline a ajuns pe server, stergem copia locala temporara. */
    suspend fun deleteLocal(id: String) {
        itemDao.deleteById(id)
    }

    // Un singur sync odata (SyncWorker si reconectarea WebSocket pot porni simultan)
    private val syncMutex = Mutex()

    /**
     * Trimite pe server postarile publice create offline. Serverul le da id-ul lui,
     * iar copia locala temporara ("local-...") se sterge, ca sa nu apara de doua ori.
     */
    suspend fun syncUnsynced() = syncMutex.withLock {
        val unsynced = itemDao.getUnsyncedItems()
        Log.d(TAG, "syncUnsynced: ${unsynced.size}")
        for (local in unsynced) {
            try {
                save(local.copy(id = "", isNotSaved = false))
                itemDao.deleteById(local.id)
            } catch (e: Exception) {
                Log.w(TAG, "syncUnsynced failed, reincercam mai tarziu", e)
                return@withLock
            }
        }
    }
    suspend fun getUnsyncedItems(): List<Post> {
        return itemDao.getUnsyncedItems()
    }

    suspend fun markAsSynced(id: String) {
        itemDao.markAsSynced(id)
    }

    private suspend fun handleItemCreated(post: Post) {
        Log.d(TAG, "handleItemCreated...")
        //Log.d(TAG,post.toString())
//        posts = listOf(post) + posts
//        //Log.d(posts.toString(),"posts")
//        postsFlow.emit(Result.Success(posts))
        // ce vine de pe server / WebSocket e mereu public
        itemDao.insert(post.copy(isPrivate = false, isNotSaved = false))
    }

    /** Postare publica salvata offline: apare imediat in lista, se trimite cand revine reteaua. */
    suspend fun saveToLocal(post: Post) {
        itemDao.insert(
            post.copy(
                id = post.id.ifEmpty { "local-" + java.util.UUID.randomUUID().toString() },
                isNotSaved = true,
                isPrivate = false
            )
        )
    }


    /** La Logout: stergem postarile publice din cache. Cele private raman pe telefon,
     *  ascunse; le vede doar proprietarul lor, dupa ce se logheaza din nou. */
    suspend fun deleteAll() {
        itemDao.deleteAllPublic()
    }

    fun setToken(token: String) {
        postWsClient.authorize(token)
    }
//    suspend fun deleteItem(item: Post) {
//        itemDao.de(item.lat, item.lon);
//    }
}