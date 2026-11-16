package com.example.myandroidapp.todo.data

import android.util.Log
import com.example.myandroidapp.core.Result
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.core.data.remote.OfflineException
import com.example.myandroidapp.core.data.remote.ServerException
import com.example.myandroidapp.core.data.remote.TokenInterceptor
import com.example.myandroidapp.core.data.remote.apiCall
import com.example.myandroidapp.core.data.remote.toUserMessage
import com.example.myandroidapp.todo.data.remote.Payload
import com.example.myandroidapp.todo.data.remote.PostEvent
import com.example.myandroidapp.todo.data.remote.PostService
import com.example.myandroidapp.todo.data.remote.PostWsClient
import com.example.myapp.todo.data.local.ItemDao
import com.example.myandroidapp.core.security.SecureStore
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import com.example.myandroidapp.todo.data.local.AuthorNoticeEntity
import com.example.myandroidapp.todo.data.local.HiddenPostEntity
import com.example.myandroidapp.todo.data.local.ModerationDao
import com.example.myandroidapp.todo.data.local.ReportEntity
import com.example.myandroidapp.todo.data.local.ReportedPost
import com.example.myandroidapp.todo.data.local.ReportStatus
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
// ~1 MB de text Base64: sub limita de ~2 MB pe rand a SQLite pe Android
private const val MAX_CACHED_PHOTO_CHARS = 1_000_000

/**
 * Postarile: serverul (REST + WebSocket) e sursa de adevar, iar baza de date locala (Room)
 * e cache-ul din care citeste interfata. AICI se decide intre retea si cache:
 *  - citire: UI-ul observa mereu Room (afisare instantanee, si offline); refresh() si
 *    WebSocket-ul aduc datele de pe server in Room, iar lista se actualizeaza singura;
 *  - creare: trimisa pe server, sau pusa in coada locala daca serverul nu raspunde
 *    (se trimite automat la reconectare: SyncWorker / WebSocket);
 *  - editare / stergere: doar cu serverul disponibil (altfel OfflineException, cu mesaj clar).
 * ViewModel-urile nu stiu nimic despre retea.
 */
class PostRepository(
    private val postService: PostService,
    private val postWsClient: PostWsClient,
    private val itemDao: ItemDao,
    private val moderationDao: ModerationDao,
    // cheia contului conectat acum (id-ul de pe server): doar postarile lui din coada se trimit
    private val currentOwnerKey: suspend () -> String
) {
    private var posts: kotlin.collections.List<Post> = kotlin.collections.listOf()


    private var postsFlow: MutableSharedFlow<Result<List<Post>>> = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Doar postarile publice (lista principala)
    val itemStream by lazy { itemDao.getAll() }

    // "Postarile mele": postarile publice ale unui utilizator
    fun publicItemsOf(ownerKey: String): Flow<List<Post>> = itemDao.getPublicByOwner(ownerKey)

    // ---------- Moderare (roluri) ----------

    /** Utilizatorul raporteaza o postare (o singura raportare per utilizator si postare). */
    suspend fun reportPost(postId: String, reporterId: String, reason: String) {
        val snippet = itemDao.findById(postId)?.description.orEmpty().take(140)
        moderationDao.insertReport(
            ReportEntity(postId = postId, reporterId = reporterId, reason = reason, postSnippet = snippet)
        )
    }

    /** Admin: postarile cu raportari la care inca nu s-a raspuns. */
    fun reportedPosts(): Flow<List<ReportedPost>> = moderationDao.observeReportedPosts()

    fun reportedBy(reporterId: String): Flow<List<String>> = moderationDao.observeReportedBy(reporterId)

    /** "Raportarile mele": raportarile utilizatorului, cu statusul si raspunsul adminului. */
    fun reportsOf(reporterId: String): Flow<List<ReportEntity>> = moderationDao.observeReportsOf(reporterId)

    /** Adminul respinge raportarile unei postari (postarea ramane) si le trimite un mesaj. */
    suspend fun rejectReports(postId: String, adminId: String, reply: String) {
        moderationDao.answerReports(
            postId = postId,
            status = ReportStatus.REJECTED,
            reply = reply,
            adminId = adminId,
            resolvedAt = System.currentTimeMillis()
        )
    }

    /** Admin: fiecare raportare in asteptare (cine a raportat, motivul, cand). */
    fun pendingReports(): Flow<List<ReportEntity>> = moderationDao.observePendingReports()

    /** Mesajele necitite pentru autorul cu acest ownerKey (postari sterse de admin). */
    fun unreadNoticesFor(ownerKey: String): Flow<List<AuthorNoticeEntity>> =
        moderationDao.observeUnreadNotices(ownerKey)

    suspend fun markNoticesRead(ownerKey: String) = moderationDao.markNoticesRead(ownerKey)

    /** Mesajul pentru autorul unei postari sterse de admin (citit cat timp postarea inca exista). */
    private suspend fun removalNotice(postId: String, adminId: String, message: String): AuthorNoticeEntity? {
        val post = itemDao.findById(postId) ?: return null
        if (post.user_id.isBlank()) return null
        val reasons = moderationDao.pendingReasons(postId).distinct().joinToString(" • ")
        return AuthorNoticeEntity(
            recipientKey = post.user_id,
            postId = postId,
            postSnippet = post.description.take(140),
            reasons = reasons,
            message = message,
            adminId = adminId
        )
    }

    /**
     * Adminul da dreptate raportarilor: postarea e eliminata (pe server), cei care au raportat
     * primesc raspunsul [reply], iar autorul postarii primeste mesajul [authorMessage].
     */
    suspend fun resolveReportsByDeleting(postId: String, adminId: String, reply: String, authorMessage: String) {
        deletePost(postId, adminId, authorMessage = authorMessage, reporterReply = reply)
    }

    /**
     * Sterge o postare (autorul ei sau un admin). Intai pe SERVER: daca nu raspunde, arunca
     * OfflineException si nu se schimba nimic (stergerea are nevoie de conexiune). Dupa ce
     * serverul confirma, postarea dispare si din cache, iar raportarile ei se inchid.
     * [authorMessage] != null -> autorul primeste un mesaj (admin care sterge postarea altcuiva).
     */
    suspend fun deletePost(
        postId: String,
        actorId: String,
        authorMessage: String? = null,
        reporterReply: String = "Postarea a fost ștearsă."
    ) {
        val notice = authorMessage?.let { removalNotice(postId, actorId, it) }
        // postarile din coada (inca netrimise) si cele private exista doar pe telefon
        if (!postId.startsWith("local-") && !postId.startsWith("private-")) {
            try {
                val response = apiCall { postService.delete(itemId = postId) }
                // 404 = a fost deja stearsa pe server: o scoatem si din cache
                if (!response.isSuccessful && response.code() != 404) {
                    throw ServerException(response.code(), "Postarea nu a putut fi ștearsă pe server.")
                }
            } catch (e: ServerException) {
                if (e.status != 404) throw e
            }
        }
        moderationDao.answerReports(
            postId = postId,
            status = ReportStatus.RESOLVED,
            reply = reporterReply,
            adminId = actorId,
            resolvedAt = System.currentTimeMillis()
        )
        notice?.let { moderationDao.insertNotice(it) }
        moderationDao.hide(HiddenPostEntity(postId, actorId))
        itemDao.deleteById(postId)
    }

    // ---------- Editare (Update din CRUD) ----------

    suspend fun findPost(id: String): Post? = itemDao.findById(id)

    /** Rezultatul editarii: salvata pe server, sau (postare inca in coada) doar in coada locala. */
    enum class UpdateResult { SAVED, SAVED_LOCALLY_ONLY }

    /**
     * Editeaza o postare publica.
     *  - postare din coada (creata offline, inca netrimisa): se modifica in coada, pleaca la sync;
     *  - postare de pe server: se trimite INTAI la server; raspunsul serverului ajunge in cache,
     *    iar lista se actualizeaza singura. Offline -> OfflineException (editarea cere conexiune).
     */
    suspend fun updatePost(post: Post): UpdateResult {
        val now = java.util.Date().toInstant().toString()
        val edited = post.copy(updated_at = now)
        if (edited.isNotSaved || edited.id.startsWith("local-")) {
            itemDao.insert(edited)
            return UpdateResult.SAVED_LOCALLY_ONLY
        }
        val saved = apiCall { postService.update(itemId = edited.id, item = edited) }
        handleItemUpdated(saved)
        return UpdateResult.SAVED
    }

    /**
     * Doar postarile private ale proprietarului dat (sectiunea privata).
     * In baza de date descrierea si poza sunt CRIPTATE (Android Keystore); le decriptam aici,
     * pe un fir de fundal. Postarile private vechi, necriptate, se citesc neschimbate.
     */
    fun privateItemStream(ownerKey: String): Flow<List<Post>> = itemDao.getPrivate(ownerKey)
        .map { posts ->
            posts.map {
                it.copy(
                    description = SecureStore.decrypt(it.description),
                    photo = SecureStore.decrypt(it.photo)
                )
            }
        }
        .flowOn(Dispatchers.Default)

    /** Postare privata: se salveaza doar local si CRIPTAT, nu ajunge niciodata pe server. */
    suspend fun savePrivate(post: Post): Post {
        val privatePost = post.copy(
            id = post.id.ifEmpty { "private-" + java.util.UUID.randomUUID().toString() },
            isPrivate = true,
            isNotSaved = false
        )
        val encrypted = withContext(Dispatchers.Default) {
            privatePost.copy(
                description = SecureStore.encrypt(privatePost.description),
                photo = SecureStore.encrypt(privatePost.photo)
            )
        }
        itemDao.insert(encrypted)
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

    /**
     * Aduce postarile de pe server in cache. Intoarce null la succes, sau un mesaj pentru
     * utilizator (ex. offline: "se afiseaza postarile salvate"); lista din cache ramane.
     */
    suspend fun refresh(): String? {
        Log.d(TAG, "refresh started")
        return try {
            posts = apiCall { postService.find() }
            // intr-o singura tranzactie: lista nu "clipeste" goala; raman postarile private
            // si cele create offline (inca netrimise)
            itemDao.replacePublicSynced(posts.map { it.copy(isPrivate = false, isNotSaved = false).safeForCache() })
            Log.d(TAG, "refresh succeeded: ${posts.size}")
            null
        } catch (e: OfflineException) {
            Log.w(TAG, "refresh: server indisponibil", e)
            "Ești offline. Se afișează postările salvate pe telefon."
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
            e.toUserMessage("Postările nu au putut fi actualizate.")
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
        Log.d(TAG, "update ${item.id}...")
        val updatedItem = apiCall { postService.update(itemId = item.id, item = item) }
        Log.d(TAG, "update $item succeeded")
        handleItemUpdated(updatedItem)
        return updatedItem
    }

    /** Trimite postarea pe server; serverul ii da id-ul si anunta prin WebSocket ceilalti clienti. */
    suspend fun save(item: Post): Post {
        Log.d(TAG, "save...")
        val createdItem = apiCall { postService.create(item = item) }
        Log.d(TAG, "save succeeded ${createdItem.id}")
        handleItemCreated(createdItem)
        return createdItem
    }

    /** Rezultatul crearii: trimisa pe server, sau pusa in coada (server indisponibil). */
    enum class CreateResult { SENT, QUEUED }

    /**
     * Postare noua: pe server daca raspunde; altfel in coada locala (apare imediat in lista,
     * marcata "in asteptare", si se trimite automat cand revine conexiunea).
     * Daca serverul RESPINGE postarea (ex. 403, date invalide), eroarea ajunge la utilizator.
     */
    suspend fun createPost(post: Post): CreateResult = try {
        save(post.copy(id = "", isNotSaved = false))
        CreateResult.SENT
    } catch (e: OfflineException) {
        Log.d(TAG, "server indisponibil: postarea intra in coada")
        saveToLocal(post)
        CreateResult.QUEUED
    }

    /**
     * Plasa de siguranta: SQLite pe Android nu poate citi un rand mai mare de ~2 MB, deci o
     * postare cu o poza uriasa (ex. creata de o versiune veche a aplicatiei) ar opri aplicatia
     * la afisarea listei. O pastram in cache fara poza, in loc sa blocam aplicatia.
     */
    private fun Post.safeForCache(): Post =
        if (photo.length > MAX_CACHED_PHOTO_CHARS) {
            Log.w(TAG, "postarea $id are o poza prea mare (${photo.length / 1024} KB), se salveaza fara poza")
            copy(photo = "")
        } else this

    /** Cate postari asteapta sa fie trimise (afisat in bannerul offline). */
    fun pendingCount(): Flow<Int> = itemDao.observeUnsyncedCount()

    private suspend fun handleItemDeleted(item: Post) {
        Log.d(TAG, "handleItemDeleted ${item.id}")
        itemDao.deleteById(item.id)
    }

    private suspend fun handleItemUpdated(post: Post) {
        Log.d(TAG, "handleItemUpdated ${post.id}")
        // scriem in baza locala -> lista (Flow din Room) se actualizeaza singura
        itemDao.insert(post.copy(isPrivate = false, isNotSaved = false).safeForCache())
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
        val token = Api.tokenInterceptor.token
        // fara sesiune de pe server (ex. login offline), serverul nu ar accepta postarile
        if (token.isNullOrEmpty() || TokenInterceptor.isLocalToken(token)) return@withLock
        // doar postarile contului conectat acum (nu ale altui cont de pe acelasi telefon)
        val ownerKey = currentOwnerKey()
        val unsynced = itemDao.getUnsyncedItems().filter { it.user_id == ownerKey }
        Log.d(TAG, "syncUnsynced: ${unsynced.size}")
        for (local in unsynced) {
            try {
                save(local.copy(id = "", isNotSaved = false))
                itemDao.deleteById(local.id)
            } catch (e: ServerException) {
                // serverul a refuzat-o definitiv (ex. date invalide): nu o retrimitem la nesfarsit
                Log.w(TAG, "postarea din coada a fost refuzata de server: ${e.message}")
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
        itemDao.insert(post.copy(isPrivate = false, isNotSaved = false).safeForCache())
    }

    /** Postare publica salvata offline: apare imediat in lista, se trimite cand revine reteaua. */
    suspend fun saveToLocal(post: Post) {
        itemDao.insert(
            post.copy(
                id = post.id.ifEmpty { "local-" + java.util.UUID.randomUUID().toString() },
                isNotSaved = true,
                isPrivate = false
            ).safeForCache()
        )
    }


    /** La Logout: stergem postarile publice din cache. Cele private raman pe telefon,
     *  ascunse; le vede doar proprietarul lor, dupa ce se logheaza din nou. */
    suspend fun deleteAll() {
        itemDao.deleteAllPublicSynced()
    }

    fun setToken(token: String) {
        postWsClient.authorize(token)
    }
//    suspend fun deleteItem(item: Post) {
//        itemDao.de(item.lat, item.lon);
//    }
}