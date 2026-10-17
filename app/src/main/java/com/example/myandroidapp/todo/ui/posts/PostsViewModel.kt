package com.example.myandroidapp.todo.ui.posts

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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Lista principala. Lista vine direct din Room (Flow), deci orice schimbare in baza
 * de date (WebSocket, sincronizare, stergere, raportare) apare imediat, fara refresh.
 */
class PostsViewModel(
    private val postRepository: PostRepository,
    private val roleRepository: RoleRepository
) : ViewModel() {
    val uiState: Flow<List<Post>> = postRepository.itemStream

    /** Contul logat, cu rolul lui (null = vizitator). */
    val currentUser: StateFlow<UserEntity?> = roleRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Id-urile postarilor raportate deja de utilizatorul curent. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val reportedByMe: StateFlow<Set<String>> = roleRepository.currentUser
        .flatMapLatest { user -> if (user == null) flowOf(emptyList()) else postRepository.reportedBy(user.email) }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    var isRefreshing by mutableStateOf(false)
        private set

    // Confirmari / erori scurte (snackbar): "Postare ștearsă", "Raportare trimisă" etc.
    var message by mutableStateOf<String?>(null)
        private set

    init {
        Log.d(TAG, "init")
        loadItems()
    }

    fun loadItems() {
        Log.d(TAG, "loadItems...")
        if (isRefreshing) return
        viewModelScope.launch {
            isRefreshing = true
            postRepository.refresh()
            isRefreshing = false
        }
    }

    fun consumeMessage() {
        message = null
    }

    /** ADMIN: orice postare. USER: doar postarile proprii. */
    fun canDelete(post: Post, user: UserEntity?): Boolean =
        user != null && (user.isAdmin || post.user_id == user.ownerKey)

    /** Doar USER (nu admin, nu vizitator) poate raporta postarile altora. */
    fun canReport(post: Post, user: UserEntity?): Boolean =
        user != null && !user.isAdmin && post.user_id != user.ownerKey

    fun deletePost(post: Post) {
        viewModelScope.launch {
            val user = roleRepository.currentUserOnce()
            if (user == null || !canDelete(post, user)) {
                message = "Nu aveți dreptul să ștergeți această postare."
                return@launch
            }
            try {
                postRepository.deletePost(post.id, user.email)
                message = "Postarea a fost ștearsă."
            } catch (e: Exception) {
                Log.w(TAG, "delete failed", e)
                message = "Postarea nu a putut fi ștearsă."
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
                PostsViewModel(app.container.postRepository, app.container.roleRepository)
            }
        }
    }
}
