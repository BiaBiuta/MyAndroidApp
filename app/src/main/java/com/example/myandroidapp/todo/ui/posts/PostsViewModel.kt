package com.example.myandroidapp.todo.ui.posts

import com.example.myandroidapp.core.data.remote.toUserMessage
import com.example.myandroidapp.core.data.remote.OfflineException
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.auth.data.RoleRepository
import com.example.myandroidapp.auth.data.local.UserEntity
import com.example.myandroidapp.auth.data.local.isAdmin
import com.example.myandroidapp.auth.data.local.isGuest
import com.example.myandroidapp.auth.data.local.isRegularUser
import com.example.myandroidapp.todo.data.local.AuthorNoticeEntity
import com.example.myandroidapp.todo.ui.admin.AdminViewModel
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.todo.data.Post
import com.example.myandroidapp.todo.data.PostRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import com.example.myandroidapp.todo.data.PostStatus
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Lista principala. Lista vine direct din Room (Flow), deci orice schimbare in baza
 * de date (WebSocket, sincronizare, stergere, raportare) apare imediat, fara refresh.
 */
class PostsViewModel(
    private val postRepository: PostRepository,
    private val roleRepository: RoleRepository,
    private val community: com.example.myandroidapp.community.data.CommunityRepository
) : ViewModel() {
    /** Calatorii urmariti si postarile salvate (ambele pastrate si pe telefon). */
    val following: StateFlow<Set<String>> = community.following.map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val saved: StateFlow<Set<String>> = community.saved.map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Salveaza / scoate din "Salvate" (si offline: doar pe telefon). */
    fun toggleSaved(post: Post) {
        viewModelScope.launch { community.setSaved(post.id, post.id !in saved.value) }
    }
    /** Contul logat, cu rolul lui (null = vizitator). */
    val currentUser: StateFlow<UserEntity?> = roleRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Cautare si filtre (lista se recalculeaza la fiecare schimbare, fara cereri la server). */
    data class Filters(
        val query: String = "",
        val mineOnly: Boolean = false,
        // 0 = orice rating; 1..5 = cel putin atatea stele
        val minRating: Int = 0,
        // doar ciornele proprii (le vede doar autorul, pana le publica)
        val draftsOnly: Boolean = false,
        // true = cele mai bine notate primele (la rating egal: cele mai noi); false = cele mai noi primele
        val sortByRating: Boolean = false,
        // doar postarile calatorilor urmariti / doar cele salvate
        val followingOnly: Boolean = false,
        val savedOnly: Boolean = false
    )

    private val _filters = MutableStateFlow(Filters())
    val filters: StateFlow<Filters> = _filters.asStateFlow()

    fun onQueryChange(value: String) = _filters.update { it.copy(query = value) }
    fun onMineOnlyChange(value: Boolean) = _filters.update { it.copy(mineOnly = value) }
    fun onMinRatingChange(value: Int) = _filters.update { it.copy(minRating = value.coerceIn(0, 5)) }
    fun onDraftsOnlyChange(value: Boolean) = _filters.update { it.copy(draftsOnly = value) }
    fun onSortByRatingChange(value: Boolean) = _filters.update { it.copy(sortByRating = value) }
    fun onFollowingOnlyChange(value: Boolean) = _filters.update { it.copy(followingOnly = value, savedOnly = false) }
    fun onSavedOnlyChange(value: Boolean) = _filters.update { it.copy(savedOnly = value, followingOnly = false) }
    fun clearFilters() = _filters.update { Filters() }

    /**
     * Feed-ul: postarile din Room (actualizate singur de server / WebSocket / sincronizare),
     * filtrate dupa ce are voie sa vada contul curent si dupa cautare / filtre.
     */
    val uiState: Flow<List<Post>> = combine(
        postRepository.itemStream, currentUser, _filters, following, saved
    ) { posts, user, f, followed, savedIds ->
        val me = user?.ownerKey
        val words = normalize(f.query).split(' ').filter { it.isNotBlank() }
        posts.filter { post ->
            val mine = me != null && post.user_id == me
            // ciornele le vede doar autorul; postarile ascunse de moderator: autorul si adminii
            val visible = (post.status != PostStatus.DRAFT || mine) && (!post.hidden || mine || user?.isAdmin == true)
            visible &&
                    (!f.mineOnly || mine) &&
                    (!f.draftsOnly || (mine && post.status == PostStatus.DRAFT)) &&
                    (!f.followingOnly || post.user_id in followed) &&
                    (!f.savedOnly || post.id in savedIds) &&
                    (f.minRating == 0 || post.rating >= f.minRating) &&
                    // in cautare intra si numele locului
                    words.all { word -> normalize("${post.title} ${post.place} ${post.description}").contains(word) }
        }.let { list ->
            // lista vine din Room deja ordonata dupa data (cele mai noi primele); sortarea e stabila
            if (f.sortByRating) list.sortedByDescending { it.rating } else list
        }
    }

    /** Pentru cautare: fara diacritice si fara majuscule ("Brașov" = "brasov"). */
    private fun normalize(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    /** Id-urile postarilor raportate deja de utilizatorul curent. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val reportedByMe: StateFlow<Set<String>> = roleRepository.currentUser
        .flatMapLatest { user -> if (user == null) flowOf(emptyList()) else postRepository.reportedBy(user.email) }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Mesajele necitite pentru utilizatorul curent: postari ale lui sterse de un administrator. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val unreadNotices: StateFlow<List<AuthorNoticeEntity>> = roleRepository.currentUser
        .flatMapLatest { user ->
            if (user == null || user.isAdmin) flowOf(emptyList())
            else postRepository.unreadNoticesFor(user.ownerKey)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Utilizatorul a citit mesajele ("Am înțeles"). */
    fun acknowledgeNotices() {
        viewModelScope.launch {
            val user = roleRepository.currentUserOnce() ?: return@launch
            postRepository.markNoticesRead(user.ownerKey)
        }
    }

    var isRefreshing by mutableStateOf(false)
        private set

    /** Postarile create offline care asteapta sa fie trimise pe server (bannerul offline). */
    var syncing by mutableStateOf(false)
        private set

    /** Butonul "Trimite acum" din banner. */
    fun syncNow() {
        if (syncing) return
        viewModelScope.launch {
            syncing = true
            val done = try {
                postRepository.syncNow()
            } catch (e: Exception) {
                Log.w(TAG, "syncNow failed", e)
                false
            }
            if (done) postRepository.refresh()
            else message = "Serverul nu răspunde încă. Postările rămân salvate și se trimit automat."
            syncing = false
        }
    }

    val pendingCount: StateFlow<Int> = postRepository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // Confirmari / erori scurte (snackbar): "Postare ștearsă", "Raportare trimisă" etc.
    var message by mutableStateOf<String?>(null)
        private set

    /** true = serverul pornit e o versiune veche: titlul, rating-ul, ciornele si locul s-ar pierde. */
    var serverOutdated by mutableStateOf(false)
        private set

    /** Numarul ciornelor proprii (pentru filtrul "Ciornele mele"). */
    val myDraftCount: StateFlow<Int> = combine(postRepository.itemStream, currentUser) { posts, user ->
        val me = user?.ownerKey
        if (me == null) 0 else posts.count { it.user_id == me && it.status == PostStatus.DRAFT }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Apreciaza / retrage aprecierea (doar online: schimba ce vad toti). */
    fun toggleLike(post: Post) {
        viewModelScope.launch {
            try {
                postRepository.setLiked(post.id, !post.liked_by_me)
            } catch (e: Exception) {
                Log.w(TAG, "like failed", e)
                message = if (e is com.example.myandroidapp.core.data.remote.OfflineException)
                    "Aprecierile au nevoie de conexiune la server."
                else e.toUserMessage("Aprecierea nu a putut fi salvată.")
            }
        }
    }

    /** Publica o ciorna proprie direct din lista (si offline). */
    fun publishDraft(post: Post) {
        viewModelScope.launch {
            val user = roleRepository.currentUserOnce()
            if (user == null || !canEdit(post, user)) {
                message = "Puteți publica doar ciornele proprii."
                return@launch
            }
            message = try {
                com.example.myandroidapp.todo.ui.post.writeResultMessage(
                    postRepository.publishDraft(post),
                    "Ciorna a fost publicată."
                )
            } catch (e: Exception) {
                Log.w(TAG, "publish failed", e)
                "Ciorna nu a putut fi publicată."
            }
        }
    }

    init {
        Log.d(TAG, "init")
        loadItems()
        // mesaje din sincronizarea din fundal (conflict, postare stearsa de moderator)
        viewModelScope.launch {
            postRepository.syncNotices.collect { message = it }
        }
    }

    fun loadItems() {
        Log.d(TAG, "loadItems...")
        if (isRefreshing) return
        viewModelScope.launch {
            isRefreshing = true
            // null = actualizat de pe server; altfel mesajul (ex. offline: se vad postarile salvate)
            postRepository.refresh()?.let { message = it }
            postRepository.isServerOutdated()?.let { serverOutdated = it }
            isRefreshing = false
        }
    }

    fun consumeMessage() {
        message = null
    }

    /** ADMIN: orice postare. USER: doar postarile proprii. */
    fun canDelete(post: Post, user: UserEntity?): Boolean =
        user != null && !user.isGuest && (user.isAdmin || post.user_id == user.ownerKey)

    /** Doar autorul isi poate edita postarea (adminul modereaza, nu rescrie postarile altora). */
    fun canEdit(post: Post, user: UserEntity?): Boolean =
        user != null && !user.isBlocked && !user.isGuest && post.user_id.isNotEmpty() && post.user_id == user.ownerKey

    /** Doar USER (nu admin, nu vizitator) poate raporta postarile altora. */
    fun canReport(post: Post, user: UserEntity?): Boolean =
        user != null && user.isRegularUser && post.user_id != user.ownerKey

    fun deletePost(post: Post) {
        viewModelScope.launch {
            val user = roleRepository.currentUserOnce()
            if (user == null || !canDelete(post, user)) {
                message = "Nu aveți dreptul să ștergeți această postare."
                return@launch
            }
            try {
                // Adminul sterge postarea altcuiva -> autorul primeste un mesaj
                val authorMessage = if (user.isAdmin && post.user_id != user.ownerKey)
                    AdminViewModel.DEFAULT_AUTHOR_MESSAGE else null
                val result = postRepository.deletePost(post.id, user.email, authorMessage)
                message = com.example.myandroidapp.todo.ui.post.writeResultMessage(result, "Postarea a fost ștearsă.")
            } catch (e: Exception) {
                Log.w(TAG, "delete failed", e)
                // doar moderarea (postarea altcuiva) cere conexiune; autorul poate sterge si offline
                message = if (e is OfflineException)
                    "Ești offline. Moderarea are nevoie de conexiune la server."
                else e.toUserMessage("Postarea nu a putut fi ștearsă.")
            }
        }
    }

    fun reportPost(post: Post, reason: String) {
        viewModelScope.launch {
            val user = roleRepository.currentUserOnce()
            if (user == null || !canReport(post, user)) {
                message = "Nu puteți raporta această postare."
                return@launch
            }
            try {
                postRepository.reportPost(post.id, user.email, reason)
                message = "Mulțumim! Raportarea a fost trimisă administratorilor."
            } catch (e: Exception) {
                Log.w(TAG, "report failed", e)
                message = "Raportarea nu a putut fi trimisă."
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
                PostsViewModel(app.container.postRepository, app.container.roleRepository, app.container.communityRepository)
            }
        }
    }
}
