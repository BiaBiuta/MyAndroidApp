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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.example.myandroidapp.todo.data.remote.ServerStats
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlin.collections.List

//post Service is an Rest item with 4 endpoints
// ~1 MB de text Base64: sub limita de ~2 MB pe rand a SQLite pe Android
private const val MAX_CACHED_PHOTO_CHARS = 1_000_000

/**
 * Postarile, OFFLINE-FIRST: baza de date locala (Room) e ce vede interfata, serverul e sursa de adevar.
 * AICI (nu in ViewModel-uri) se decide intre retea si cache:
 *  - citire: UI-ul observa mereu Room (afisare instantanee, si offline); refresh() si WebSocket-ul
 *    aduc datele de pe server in Room, iar listele se actualizeaza singure (Flow);
 *  - creare / editare / stergere: se scriu INTAI pe telefon (insigna "Pending"), apoi se trimit
 *    imediat la server daca e disponibil; altfel raman in coada si pleaca singure la reconectare
 *    (SyncWorker cu WorkManager, reconectarea WebSocket-ului);
 *  - conflicte: castiga modificarea cea mai recenta (serverul raspunde 409 cu versiunea lui);
 *    o postare stearsa intre timp de un moderator (410) se pastreaza ca ciorna privata;
 *  - date refuzate de server (400 / 413): insigna "Sincronizare esuata", cu mesajul serverului;
 *  - moderarea (ascundere, stergerea postarilor altora, statistici) cere conexiune: schimba ce vad toti.
 */
class PostRepository(
    private val postService: PostService,
    private val postWsClient: PostWsClient,
    private val itemDao: ItemDao,
    private val moderationDao: ModerationDao,
    // cheia contului conectat acum (id-ul de pe server): doar postarile lui din coada se trimit
    private val currentOwnerKey: suspend () -> String,
    private val ownerKeys: Flow<String> = flowOf(""),
    // programeaza sincronizarea cand revine reteaua (WorkManager), pentru ce a ramas in coada
    private val scheduleSync: () -> Unit = {}
) {
    /**
     * Mesaje despre sincronizarea din fundal, pentru utilizator (ex. conflict, postare stearsa
     * de moderator); ecranul principal le afiseaza ca Snackbar.
     */
    private val _syncNotices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val syncNotices: SharedFlow<String> = _syncNotices.asSharedFlow()

    private var posts: kotlin.collections.List<Post> = kotlin.collections.listOf()


    private var postsFlow: MutableSharedFlow<Result<List<Post>>> = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

//    // Doar postarile publice (lista principala)
//    val itemStream by lazy { itemDao.getAll() }
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
val itemStream: Flow<List<Post>> by lazy { ownerKeys.distinctUntilChanged().flatMapLatest { itemDao.getVisibleTo(it) } }
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
     * Sterge o postare.
     *  - autorul: offline-first. Postarea dispare imediat din liste ("tombstone", isDeleted) si se
     *    sterge pe server acum sau, fara conexiune, la urmatoarea sincronizare;
     *  - moderare (admin, postarea altcuiva; [authorMessage] != null): doar cu serverul disponibil
     *    (altfel OfflineException), pentru ca schimba ce vad toti utilizatorii.
     * Raportarile inca deschise ale postarii se inchid, cu un mesaj pentru cei care au raportat.
     */
    suspend fun deletePost(
        postId: String,
        actorId: String,
        authorMessage: String? = null,
        reporterReply: String = "Postarea a fost ștearsă.",
        moderation: Boolean = authorMessage != null
    ): WriteResult {
        val notice = authorMessage?.let { removalNotice(postId, actorId, it) }
        val result = if (postId.startsWith("private-")) {
            // postarile private exista doar pe telefon
            itemDao.deleteById(postId)
            WriteResult.Sent
        } else if (moderation) {
            deleteOnServer(postId)
            itemDao.deleteById(postId)
            WriteResult.Sent
        } else {
            itemDao.markDeleted(postId, now())
            pushOrQueue(postId)
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
        return result
    }

    /** DELETE pe server; "nu mai exista" (404 / 410) inseamna ca e deja stearsa: succes. */
    private suspend fun deleteOnServer(postId: String) {
        if (postId.startsWith("private-")) return
        try {
            val response = apiCall { postService.delete(itemId = postId) }
            if (!response.isSuccessful && response.code() != 404 && response.code() != 410) {
                throw ServerException(response.code(), "Postarea nu a putut fi ștearsă pe server.")
            }
        } catch (e: ServerException) {
            if (e.status != 404 && e.status != 410) throw e
        }
    }

    // ---------- Moderare pe server (doar ADMIN) ----------

    /** Ascunde / reafiseaza postarea oricui (dispare din feed-ul celorlalti, ramane pentru autor). */
    suspend fun setHidden(postId: String, hidden: Boolean) {
        val updated = apiCall { postService.setVisibility(postId, mapOf("hidden" to hidden)) }
        storeFromServer(updated, overwriteLocalChanges = true)
    }

    suspend fun stats(): ServerStats = apiCall { postService.stats() }

    // ---------- Editare (Update din CRUD) ----------

    suspend fun findPost(id: String): Post? = itemDao.findById(id)

    /** Rezultatul unei scrieri (creare / editare / stergere). */
    sealed interface WriteResult {
        /** A ajuns pe server. */
        data object Sent : WriteResult
        /** Salvata pe telefon ("Pending"); se trimite automat cand revine conexiunea. */
        data object Queued : WriteResult
        /** Serverul a refuzat-o; postarea are insigna "Sincronizare esuata". */
        data class Failed(val message: String) : WriteResult
        /** Conflict / postare stearsa pe server: [message] explica ce s-a pastrat. */
        data class Resolved(val message: String) : WriteResult
    }

    /** Postare noua: id generat pe telefon, salvata local, apoi trimisa (sau pusa in coada). */
    suspend fun createPost(post: Post): WriteResult {
        val time = now()
        val local = post.copy(
            id = post.id.ifEmpty { java.util.UUID.randomUUID().toString() },
            created_at = post.created_at.ifEmpty { time },
            updated_at = time,
            isPrivate = false,
            isDeleted = false
        ).markPending()
        itemDao.insert(local)
        return pushOrQueue(local.id)
    }

    /** Editare (si offline): salvata local cu ora modificarii, apoi trimisa (sau pusa in coada). */
    /** Publica o ciorna proprie (si offline: se trimite la sincronizare). */
    suspend fun publishDraft(post: Post): WriteResult = updatePost(post.copy(status = PostStatus.ACTIVE))

    /**
     * true = serverul pornit e o versiune veche (nu stie de titlu / rating / ciorna / loc);
     * false = e cel potrivit; null = nu s-a putut verifica (ex. offline).
     */
    suspend fun isServerOutdated(): Boolean? = try {
        val health = apiCall { postService.health() }
        (health.get("version")?.asInt ?: 1) < REQUIRED_SERVER_VERSION
    } catch (e: Exception) {
        null
    }

    suspend fun updatePost(post: Post): WriteResult {
        itemDao.insert(post.copy(updated_at = now()).markPending())
        return pushOrQueue(post.id)
    }

    private fun Post.markPending(): Post =
        copy(isNotSaved = true, syncStatus = SyncStatus.PENDING, syncError = "").safeForCache()

    private fun now(): String = com.example.myandroidapp.core.isoNow()

    /** Incearca imediat sincronizarea postarii; fara server, ramane in coada si se programeaza sync-ul. */
    private suspend fun pushOrQueue(postId: String): WriteResult {
        val result = if (hasServerSession()) push(postId) else WriteResult.Queued
        if (result == WriteResult.Queued) scheduleSync()
        return result
    }

    private fun hasServerSession(): Boolean {
        val token = Api.tokenInterceptor.token
        // fara sesiune de pe server (ex. login offline), serverul nu ar accepta modificarile
        return !token.isNullOrEmpty() && !TokenInterceptor.isLocalToken(token)
    }

    /**
     * Trimite o postare din coada la server si rezolva raspunsul:
     *  - stearsa offline -> DELETE (404 / 410 = deja stearsa: succes);
     *  - altfel PUT (upsert): 409 = s-a modificat altundeva mai recent -> pastram versiunea serverului;
     *    410 = stearsa de moderator -> modificarile devin ciorna privata;
     *    400 / 403 / 413 -> "Sincronizare esuata" cu mesajul serverului;
     *  - fara conexiune sau sesiune expirata (401) -> ramane "Pending".
     */
    private suspend fun push(postId: String): WriteResult {
        val local = itemDao.findById(postId) ?: return WriteResult.Sent
        if (!local.isNotSaved) return WriteResult.Sent
        return try {
            if (local.isDeleted) {
                deleteOnServer(local.id)
                itemDao.deleteById(local.id)
            } else {
                val saved = apiCall { postService.update(itemId = local.id, item = local) }
                storeFromServer(saved, overwriteLocalChanges = true)
            }
            WriteResult.Sent
        } catch (e: OfflineException) {
            WriteResult.Queued
        } catch (e: ServerException) {
            when (e.status) {
                401 -> WriteResult.Queued // sesiune expirata: se trimite dupa o noua autentificare
                409 -> {
                    serverItemFrom(e)?.let { storeFromServer(it, overwriteLocalChanges = true) }
                        ?: itemDao.deleteById(local.id)
                    val message = "„${local.displayTitle()}” a fost modificată pe alt dispozitiv. " +
                            "S-a păstrat varianta mai nouă."
                    _syncNotices.tryEmit(message)
                    WriteResult.Resolved(message)
                }
                410 -> {
                    itemDao.deleteById(local.id)
                    if (local.isDeleted) return WriteResult.Sent
                    // nimic din ce a scris utilizatorul nu se pierde: devine ciorna privata
                    savePrivate(
                        local.copy(
                            id = "",
                            status = PostStatus.DRAFT,
                            isNotSaved = false,
                            syncStatus = SyncStatus.SYNCED,
                            syncError = ""
                        )
                    )
                    val message = "„${local.displayTitle()}” a fost ștearsă de un moderator. " +
                            "Modificările tale au fost salvate ca ciornă în secțiunea privată."
                    _syncNotices.tryEmit(message)
                    WriteResult.Resolved(message)
                }
                else -> {
                    itemDao.markFailed(local.id, e.message)
                    WriteResult.Failed(e.message)
                }
            }
        }
    }

    private fun Post.displayTitle(): String =
        title.ifBlank { description.take(40) }.ifBlank { "Postarea" }

    /** Versiunea de pe server din corpul unui raspuns 409: {"message": "...", "item": {...}}. */
    private fun serverItemFrom(e: ServerException): Post? = try {
        e.body?.let { body ->
            val item = com.google.gson.JsonParser().parse(body).asJsonObject.get("item")
            item?.let { Api.gson.fromJson(it, Post::class.java) }
        }
    } catch (ex: Exception) {
        null
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
                    title = SecureStore.decrypt(it.title),
                    place = SecureStore.decrypt(it.place),
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
                title = SecureStore.encrypt(privatePost.title),
                place = SecureStore.encrypt(privatePost.place),
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
            // modificarile facute offline (inca netrimise) nu se suprascriu cu versiunea serverului
            val dirtyIds = itemDao.getDirtyIds().toSet()
            // intr-o singura tranzactie: lista nu "clipeste" goala; raman postarile private
            // si cele din coada
            itemDao.replacePublicSynced(
                posts.filter { it.id !in dirtyIds }.map { it.asSynced().safeForCache() }
            )
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
    /** Postarea, cum a confirmat-o serverul: "Synced". */
    private fun Post.asSynced(): Post =
        copy(isPrivate = false, isNotSaved = false, syncStatus = SyncStatus.SYNCED, syncError = "", isDeleted = false)

    /**
     * Scrie in cache o postare venita de la server (raspuns la o cerere sau eveniment WebSocket).
     * O postare cu modificari locale netrimise NU e suprascrisa de evenimente: ramane varianta
     * utilizatorului, care pleaca la sincronizare (si castiga daca e mai noua).
     */
    private suspend fun storeFromServer(post: Post, overwriteLocalChanges: Boolean) {
        if (!overwriteLocalChanges && itemDao.findById(post.id)?.isNotSaved == true) return
        // scriem in baza locala -> listele (Flow din Room) se actualizeaza singure
        itemDao.insert(post.asSynced().safeForCache())
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
//    fun pendingCount(): Flow<Int> = itemDao.observeUnsyncedCount()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun pendingCount(): Flow<Int> =
        ownerKeys.distinctUntilChanged().flatMapLatest { itemDao.observeUnsyncedCountFor(it) }
    private suspend fun handleItemDeleted(item: Post) {
        Log.d(TAG, "handleItemDeleted ${item.id}")
        // o editare offline a acestei postari va primi 410 la sincronizare (-> ciorna privata)
        if (itemDao.findById(item.id)?.isNotSaved == true) return
        itemDao.deleteById(item.id)
    }

    private suspend fun handleItemUpdated(post: Post) {
        Log.d(TAG, "handleItemUpdated ${post.id}")
        storeFromServer(post, overwriteLocalChanges = false)
    }

    /** Dupa ce o postare creata offline a ajuns pe server, stergem copia locala temporara. */
    suspend fun deleteLocal(id: String) {
        itemDao.deleteById(id)
    }

    // Un singur sync odata (SyncWorker si reconectarea WebSocket pot porni simultan)
    private val syncMutex = Mutex()

    /**
     * Trimite pe server tot ce s-a modificat offline (create / editate / sterse), in ordinea
     * modificarilor. Intoarce true daca n-a mai ramas nimic de trimis din cauza retelei
     * (SyncWorker reincearca mai tarziu altfel).
     */
    suspend fun syncUnsynced(): Boolean = syncMutex.withLock {
        if (!hasServerSession()) return@withLock false
        // doar postarile contului conectat acum (nu ale altui cont de pe acelasi telefon)
        val ownerKey = currentOwnerKey()
        val pending = itemDao.getUnsyncedItems().filter { it.user_id == ownerKey }
        Log.d(TAG, "syncUnsynced: ${pending.size}")
        for (post in pending) {
            if (push(post.id) == WriteResult.Queued) {
                Log.d(TAG, "syncUnsynced: server indisponibil, reincercam mai tarziu")
                return@withLock false
            }
        }
        true
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
        storeFromServer(post, overwriteLocalChanges = false)
    }

    /** Postare publica salvata offline: apare imediat in lista, se trimite cand revine reteaua. */
    suspend fun saveToLocal(post: Post) {
        itemDao.insert(
            post.copy(
                id = post.id.ifEmpty { java.util.UUID.randomUUID().toString() },
                isPrivate = false
            ).markPending()
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
/** Versiunea minima a serverului din repo (server/, GET /health) cu care merge aplicatia. */
private const val REQUIRED_SERVER_VERSION = 4
