package com.example.myandroidapp.todo.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import com.example.myandroidapp.core.logging.SafeLog
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
    // contul conectat, ca flux: lista si coada se schimba imediat la schimbarea contului
    private val ownerKeys: Flow<String> = flowOf(""),
    // sesiune facuta offline (token "local-"): incearca login-ul pe server cand acesta revine
    private val ensureServerSession: suspend () -> Boolean = { false },
    // notificari: postare noua de la altcineva / postari offline ajunse pe server
    private val onNewPostFromOthers: (Post) -> Unit = {},
    private val onOfflinePostsSynced: (Int) -> Unit = {},
    // calatoriile create offline se trimit INAINTEA postarilor (o postare le poate referi)
    private val syncBeforePosts: suspend () -> Boolean = { true },
    // dupa reimprospatarea listei: calatoriile si lista "Urmariti" de pe server
    private val afterRefresh: suspend () -> Unit = {},
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

    // Doar postarile publice (lista principala)
    // postarile nesincronizate ale altui cont de pe acelasi telefon nu apar (vezi ItemDao.getVisibleTo)
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
     * Adminul da dreptate raportarilor, dar fara stergere: postarea e ASCUNSA (dispare din feed-ul
     * celorlalti), autorul o pastreaza si o vede cu insigna "Ascunsă de moderator", dar nu o mai
     * poate face publica (serverul nu accepta campul `hidden` de la autor; doar un admin o reafiseaza).
     * Cei care au raportat primesc [reply], autorul primeste [authorMessage].
     */
    suspend fun resolveReportsByHiding(postId: String, adminId: String, reply: String, authorMessage: String) {
        val notice = removalNotice(postId, adminId, authorMessage)
        setHidden(postId, true)
        moderationDao.answerReports(
            postId = postId,
            status = ReportStatus.RESOLVED,
            reply = reply,
            adminId = adminId,
            resolvedAt = System.currentTimeMillis()
        )
        notice?.let { moderationDao.insertNotice(it) }
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
     *  - fara conexiune, sesiune expirata (401) sau server cu probleme (5xx) -> ramane "Pending".
     * Decizia pentru fiecare cod de raspuns: [SyncConflictPolicy].
     */
    private suspend fun push(postId: String): WriteResult {
        val local = itemDao.findById(postId) ?: return WriteResult.Sent
        if (!local.isNotSaved) return WriteResult.Sent
        return try {
            if (local.isDeleted) {
                deleteOnServer(local.id)
                itemDao.deleteById(local.id)
            } else {
                // intai poza (fisier separat), apoi postarea cu adresa ei: serverul nu primeste
                // niciodata o postare fara poza ei
                val ready = uploadPhotoIfNeeded(local)
                val saved = apiCall { postService.update(itemId = ready.id, item = ready) }
                storeFromServer(saved, overwriteLocalChanges = true)
            }
            WriteResult.Sent
        } catch (e: OfflineException) {
            WriteResult.Queued
        } catch (e: ServerException) {
            val title = SyncConflictPolicy.displayTitle(local.title, local.description)
            when (SyncConflictPolicy.onServerError(e.status, deletedLocally = local.isDeleted)) {
                // sesiune expirata / server ocupat: ramane "Pending", se reincearca automat
                SyncConflictPolicy.Outcome.RETRY_LATER -> WriteResult.Queued
                SyncConflictPolicy.Outcome.ALREADY_DELETED -> {
                    itemDao.deleteById(local.id)
                    WriteResult.Sent
                }
                SyncConflictPolicy.Outcome.KEEP_SERVER_VERSION -> {
                    serverItemFrom(e)?.let { storeFromServer(it, overwriteLocalChanges = true) }
                        ?: itemDao.deleteById(local.id)
                    val message = SyncConflictPolicy.keptServerVersionMessage(title)
                    _syncNotices.tryEmit(message)
                    WriteResult.Resolved(message)
                }
                SyncConflictPolicy.Outcome.SAVE_AS_PRIVATE_DRAFT -> {
                    itemDao.deleteById(local.id)
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
                    val message = SyncConflictPolicy.savedAsDraftMessage(title)
                    _syncNotices.tryEmit(message)
                    WriteResult.Resolved(message)
                }
                SyncConflictPolicy.Outcome.FAILED -> {
                    itemDao.markFailed(local.id, e.message)
                    WriteResult.Failed(e.message)
                }
            }
        }
    }

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
                    photo = SecureStore.decrypt(it.photo),
                    gallery = it.gallery.map(SecureStore::decrypt)
                )
            }
        }
        .flowOn(Dispatchers.Default)

    /** Postare privata: se salveaza doar local si CRIPTAT, nu ajunge niciodata pe server. */
    suspend fun savePrivate(post: Post): Post {
        // poza ca fisier -> criptata in baza de date, ca restul postarii private; fisierul se sterge
        val withPhoto = withContext(Dispatchers.IO) {
            val path = post.photoPath
            val withCover = if (path.isBlank()) post
            else {
                val inline = com.example.myandroidapp.todo.ui.post.PhotoEncoder.fileToBase64(path).orEmpty()
                runCatching { java.io.File(path).delete() }
                post.copy(photo = inline.ifBlank { post.photo }, photoPath = "", photo_url = "")
            }
            // si galeria: fiecare poza devine Base64 (criptat mai jos), fisierele se sterg
            withCover.copy(gallery = withCover.gallery.mapNotNull { entry ->
                if (entry.startsWith("data:")) entry
                else com.example.myandroidapp.todo.ui.post.PhotoEncoder.fileToBase64(entry)
                    ?.also { runCatching { java.io.File(entry).delete() } }
            })
        }
        val privatePost = withPhoto.copy(
            id = post.id.ifEmpty { "private-" + java.util.UUID.randomUUID().toString() },
            isPrivate = true,
            isNotSaved = false
        )
        val encrypted = withContext(Dispatchers.Default) {
            privatePost.copy(
                title = SecureStore.encrypt(privatePost.title),
                place = SecureStore.encrypt(privatePost.place),
                description = SecureStore.encrypt(privatePost.description),
                photo = SecureStore.encrypt(privatePost.photo),
                gallery = privatePost.gallery.map(SecureStore::encrypt)
            )
        }
        itemDao.insert(encrypted)
        return privatePost
    }

    init {
        SafeLog.d(TAG, "init")
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
        SafeLog.d(TAG, "refresh started")
        return try {
            posts = apiCall { postService.find() }
            // modificarile facute offline (inca netrimise) nu se suprascriu cu versiunea serverului
            val dirtyIds = itemDao.getDirtyIds().toSet()
            // intr-o singura tranzactie: lista nu "clipeste" goala; raman postarile private
            // si cele din coada
            itemDao.replacePublicSynced(
                posts.filter { it.id !in dirtyIds }.map { it.asSynced().safeForCache() }
            )
            SafeLog.d(TAG, "refresh succeeded: ${posts.size}")
            runCatching { afterRefresh() }
            null
        } catch (e: OfflineException) {
            SafeLog.w(TAG, "refresh: server indisponibil", e)
            "Ești offline. Se afișează postările salvate pe telefon."
        } catch (e: Exception) {
            SafeLog.w(TAG, "refresh failed", e)
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
        SafeLog.d(TAG, "openWsClient #$generation")
        withContext(Dispatchers.IO) {
            // Cat timp aplicatia e deschisa: daca exista postari in coada, reincercam trimiterea la
            // fiecare 10 s. Nu depinde de WebSocket sau de WorkManager (care asteapta tot mai mult
            // intre incercari): cand serverul revine, postarile pleaca in cateva secunde.
            launch {
                while (isActive && generation == wsGeneration) {
                    delay(FOREGROUND_RETRY_MS)
                    if (generation != wsGeneration) break
                    val owner = currentOwnerKey()
                    val waiting = itemDao.getUnsyncedItems().any { it.user_id == owner }
                    if (waiting) {
                        SafeLog.d(TAG, "retry: postari in coada, incercam trimiterea")
                        if (syncUnsynced()) refresh()
                    }
                }
            }
            var backoffMs = 2_000L
            while (isActive && generation == wsGeneration) {
                var opened = false
                getPostEvents(onOpen = { opened = true }).collect {
                    // doar tipul evenimentului si id-ul postarii, nu continutul ei
                    SafeLog.d(TAG, "eveniment primit: " + if (it is Result.Success) "${it.data.event} ${it.data.payload.item.id}" else "eroare")
                    if (it is Result.Success) {
                        val itemEvent = it.data
                        when (itemEvent.event) {
                            "created" -> handleItemCreated(itemEvent.payload.item)
                            "updated" -> handleItemUpdated(itemEvent.payload.item)
                            "deleted" -> handleItemDeleted(itemEvent.payload.item)
                            "comments" -> _commentChanges.tryEmit(itemEvent.payload.item.id)
                        }
                    }
                }
                // socket-ul s-a inchis sau nu s-a putut conecta
                if (generation != wsGeneration) break
                if (opened) backoffMs = 2_000L
                SafeLog.d(TAG, "WebSocket inchis, reconectare in ${backoffMs}ms")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    suspend fun closeWsClient() {
        SafeLog.d(TAG, "closeWsClient")
        wsGeneration++
        withContext(Dispatchers.IO) {
            postWsClient.closeSocket()
        }
    }

    fun getPostEvents(onOpen: () -> Unit = {}): Flow<Result<PostEvent>> = callbackFlow {
        SafeLog.d(TAG, "getItemEvents started")
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
                    SafeLog.d(TAG, "onEvent ${it.event} ${it.payload.item.id}")
                    trySend(Result.Success(it))
                }
            },
            onClosed = { close() },
            onFailure = { close() })
        awaitClose { postWsClient.closeSocket(socket) }
    }
    /**
     * Urca poza postarii daca e doar pe telefon. Id-ul pozei = numele fisierului (UUID), deci o
     * reincercare dupa o conexiune cazuta nu o dubleaza. Adresa primita se salveaza imediat local.
     */
    private suspend fun uploadPhotoIfNeeded(post: Post): Post {
        val withCover = uploadCoverIfNeeded(post)
        // galeria: fiecare poza locala se urca si e inlocuita cu adresa ei
        if (withCover.gallery.none { !it.startsWith("/photos/") }) return withCover
        val urls = withCover.gallery.mapNotNull { entry ->
            if (entry.startsWith("/photos/")) entry
            else {
                val file = java.io.File(entry)
                if (!file.exists()) null
                else apiCall { postService.uploadPhoto(file.nameWithoutExtension, file.asRequestBody("image/jpeg".toMediaType())) }.url
            }
        }
        val uploaded = withCover.copy(gallery = urls)
        itemDao.insert(uploaded)
        return uploaded
    }

    private suspend fun uploadCoverIfNeeded(post: Post): Post {
        if (post.photoPath.isBlank() || post.photo_url.isNotBlank()) return post
        val file = java.io.File(post.photoPath)
        if (!file.exists()) {
            // fisierul a disparut (ex. date sterse): postarea pleaca fara poza, nu ramane blocata
            return post.copy(photoPath = "").also { itemDao.insert(it) }
        }
        val photoId = file.nameWithoutExtension
        val body = file.asRequestBody("image/jpeg".toMediaType())
        val uploaded = apiCall { postService.uploadPhoto(photoId, body) }
        val withUrl = post.copy(photo_url = uploaded.url)
        itemDao.insert(withUrl)
        return withUrl
    }

    // ---------- aprecieri si comentarii: schimba ce vad toti, deci doar online ----------

    /** Apreciaza / retrage aprecierea; postarea actualizata (numarul) ajunge in cache. */
    suspend fun setLiked(postId: String, liked: Boolean): Post {
        val updated = apiCall { if (liked) postService.like(postId) else postService.unlike(postId) }
        storeFromServer(updated, overwriteLocalChanges = false)
        return updated
    }

    suspend fun comments(postId: String): List<com.example.myandroidapp.todo.data.remote.Comment> =
        apiCall { postService.comments(postId) }

    /** Comentariu nou; parentId = raspuns la acel comentariu. */
    suspend fun addComment(postId: String, text: String, parentId: String = ""): com.example.myandroidapp.todo.data.remote.Comment =
        apiCall { postService.addComment(postId, mapOf("text" to text, "parent_id" to parentId)) }

    // id-ul postarii ale carei comentarii s-au schimbat (eveniment WebSocket "comments")
    private val _commentChanges = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)
    val commentChanges: kotlinx.coroutines.flow.SharedFlow<String> = _commentChanges

    suspend fun deleteComment(commentId: String) {
        val response = apiCall { postService.deleteComment(commentId) }
        if (!response.isSuccessful && response.code() != 404) {
            throw com.example.myandroidapp.core.data.remote.ServerException(response.code(), "Comentariul nu a putut fi șters.")
        }
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
        val local = itemDao.findById(post.id)
        if (!overwriteLocalChanges && local?.isNotSaved == true) return
        // poza facuta pe acest telefon ramane afisata din fisier (fara descarcare), daca e aceeasi
        val keepFile = local?.photoPath?.takeIf { it.isNotBlank() && local.photo_url == post.photo_url }.orEmpty()
        // scriem in baza locala -> listele (Flow din Room) se actualizeaza singure
        itemDao.insert(post.asSynced().copy(photoPath = keepFile).safeForCache())
    }

    /**
     * Plasa de siguranta: SQLite pe Android nu poate citi un rand mai mare de ~2 MB, deci o
     * postare cu o poza uriasa (ex. creata de o versiune veche a aplicatiei) ar opri aplicatia
     * la afisarea listei. O pastram in cache fara poza, in loc sa blocam aplicatia.
     */
    private fun Post.safeForCache(): Post =
        if (photo.length > MAX_CACHED_PHOTO_CHARS) {
            SafeLog.w(TAG, "postarea $id are o poza prea mare (${photo.length / 1024} KB), se salveaza fara poza")
            copy(photo = "")
        } else this

    /** Cate postari asteapta sa fie trimise (afisat in bannerul offline). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun pendingCount(): Flow<Int> =
        ownerKeys.distinctUntilChanged().flatMapLatest { itemDao.observeUnsyncedCountFor(it) }

    private suspend fun handleItemDeleted(item: Post) {
        SafeLog.d(TAG, "handleItemDeleted ${item.id}")
        // o editare offline a acestei postari va primi 410 la sincronizare (-> ciorna privata)
        if (itemDao.findById(item.id)?.isNotSaved == true) return
        itemDao.deleteById(item.id)
    }

    private suspend fun handleItemUpdated(post: Post) {
        SafeLog.d(TAG, "handleItemUpdated ${post.id}")
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
    /** "Trimite acum" din banner: incercare imediata; intoarce true daca nu mai e nimic in coada. */
    suspend fun syncNow(): Boolean {
        val done = syncUnsynced()
        if (!done) scheduleSync()
        return done
    }

    suspend fun syncUnsynced(): Boolean = syncMutex.withLock {
        // login facut offline: trecem intai sesiunea pe server (altfel serverul n-ar accepta nimic)
        if (!hasServerSession() && !ensureServerSession()) return@withLock false
        if (!hasServerSession()) return@withLock false
        if (!syncBeforePosts()) return@withLock false
        // doar postarile contului conectat acum (nu ale altui cont de pe acelasi telefon)
        val ownerKey = currentOwnerKey()
        val pending = itemDao.getUnsyncedItems().filter { it.user_id == ownerKey }
        SafeLog.d(TAG, "syncUnsynced: ${pending.size}")
        var published = 0
        for (post in pending) {
            val result = push(post.id)
            if (result == WriteResult.Sent && !post.isDeleted) published++
            if (result == WriteResult.Queued) {
                SafeLog.d(TAG, "syncUnsynced: server indisponibil, reincercam mai tarziu")
                onOfflinePostsSynced(published)
                return@withLock false
            }
        }
        onOfflinePostsSynced(published)
        true
    }

    suspend fun getUnsyncedItems(): List<Post> {
        return itemDao.getUnsyncedItems()
    }

    suspend fun markAsSynced(id: String) {
        itemDao.markAsSynced(id)
    }

    private suspend fun handleItemCreated(post: Post) {
        SafeLog.d(TAG, "handleItemCreated...")
        // postare noua de la ALTCINEVA (nu o copie a uneia pe care o avem deja) -> notificare
        val known = itemDao.findById(post.id) != null
        if (!known && post.user_id != currentOwnerKey() && post.status != PostStatus.DRAFT) {
            onNewPostFromOthers(post)
        }
        //SafeLog.d(TAG,post.toString())
//        posts = listOf(post) + posts
//        //SafeLog.d(posts.toString(),"posts")
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
private const val REQUIRED_SERVER_VERSION = 5

/** Cat de des reincearca aplicatia deschisa sa trimita postarile din coada. */
private const val FOREGROUND_RETRY_MS = 10_000L
