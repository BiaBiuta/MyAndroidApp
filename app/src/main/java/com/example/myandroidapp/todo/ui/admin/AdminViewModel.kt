package com.example.myandroidapp.todo.ui.admin

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
import com.example.myandroidapp.auth.data.local.UserRole
import com.example.myandroidapp.auth.data.local.isAdmin
import com.example.myandroidapp.todo.data.Post
import com.example.myandroidapp.todo.data.PostRepository
import com.example.myandroidapp.todo.data.remote.ServerStats
import com.example.myandroidapp.todo.data.local.ReportEntity
import com.example.myandroidapp.todo.data.local.ReportedPost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Functionalitati doar pentru rolul ADMIN:
 *  1. Moderare: postarile raportate -> sterge postarea sau respinge raportarea
 *  2. Gestionare utilizatori: promovare / retrogradare, blocare / deblocare
 * Fiecare actiune verifica din nou rolul in RoleRepository (nu ne bazam doar pe UI).
 */
class AdminViewModel(
    private val postRepository: PostRepository,
    private val userRepository: RoleRepository
) : ViewModel() {

    val reportedPosts: Flow<List<ReportedPost>> = postRepository.reportedPosts()
    val users: Flow<List<UserEntity>> = userRepository.observeAllUsers()

    /** Raportarile in asteptare, grupate pe postare: cine a raportat, motivul si cand. */
    val pendingReportsByPost: Flow<Map<String, List<ReportEntity>>> =
        postRepository.pendingReports().map { reports -> reports.groupBy { it.postId } }
    val currentUser: StateFlow<UserEntity?> = userRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var busy by mutableStateOf(false)
        private set

    /** Aduce utilizatorii de pe server; offline, se vede lista salvata pe telefon. */
    /** Toate postarile din feed (inclusiv cele ascunse, pe care serverul le trimite doar adminilor). */
    val allPosts: Flow<List<Post>> = postRepository.itemStream

    /** Statisticile de pe server (null = inca neincarcate sau offline). */
    var stats by mutableStateOf<ServerStats?>(null)
        private set

    /** Reincarca tot ce vede moderatorul: statistici, postari, utilizatori. */
    fun refreshAll() {
        refreshStats()
        refreshUsers()
        viewModelScope.launch { postRepository.refresh()?.let { message = it } }
    }

    fun refreshStats() {
        viewModelScope.launch {
            stats = try {
                postRepository.stats()
            } catch (e: Exception) {
                Log.w("AdminViewModel", "stats failed", e)
                null
            }
        }
    }

    /** Ascunde / reafiseaza postarea oricui (doar cu conexiune: schimba ce vad toti). */
    fun toggleHidden(post: Post) = runAdmin(
        if (post.hidden) "Postarea este din nou vizibilă tuturor." else "Postarea a fost ascunsă din feed."
    ) {
        postRepository.setHidden(post.id, !post.hidden)
        refreshStats()
        null
    }

    /** Moderare: sterge postarea altcuiva; autorul primeste un mesaj. */
    fun deleteAsModerator(post: Post) = runAdmin("Postarea a fost ștearsă. Autorul a primit un mesaj.") {
        val admin = userRepository.currentUserOnce() ?: return@runAdmin "Sesiune expirată."
        postRepository.deletePost(post.id, admin.email, authorMessage = DEFAULT_AUTHOR_MESSAGE, moderation = true)
        refreshStats()
        null
    }

    fun refreshUsers() {
        viewModelScope.launch {
            busy = true
            userRepository.refreshUsersFromServer()?.let { message = it }
            busy = false
        }
    }
    var message by mutableStateOf<String?>(null)
        private set

    init {
        // utilizatorii, postarile si statisticile vin de pe server
        refreshAll()
    }

    fun consumeMessage() {
        message = null
    }

    private fun runAdmin(success: String, block: suspend () -> String?) {
        viewModelScope.launch {
            if (currentUser.value?.isAdmin != true && userRepository.currentUserOnce()?.isAdmin != true) {
                message = "Doar administratorii pot face această acțiune."
                return@launch
            }
            busy = true
            message = try {
                block() ?: success
            } catch (e: Exception) {
                Log.w("AdminViewModel", "admin action failed", e)
                if (e is OfflineException) "Ești offline. Această acțiune are nevoie de conexiune la server."
                else e.toUserMessage("Acțiunea nu a putut fi finalizată.")
            }
            busy = false
        }
    }

    /**
     * Da dreptate raportarilor: postarea e eliminata, cei care au raportat vad [reply],
     * iar autorul postarii primeste [authorMessage].
     */
    fun deleteReportedPost(reported: ReportedPost, reply: String, authorMessage: String) =
        runAdmin("Postarea a fost eliminată. Autorul și cei care au raportat au primit mesajul.") {
            val admin = userRepository.currentUserOnce() ?: return@runAdmin "Sesiune expirată."
            postRepository.resolveReportsByDeleting(
                postId = reported.post.id,
                adminId = admin.email,
                reply = reply.trim().ifBlank { DEFAULT_RESOLVED_REPLY },
                authorMessage = authorMessage.trim().ifBlank { DEFAULT_AUTHOR_MESSAGE }
            )
            null
        }

    /** Respinge raportarile: postarea ramane, iar cei care au raportat vad mesajul adminului. */
    fun dismissReports(reported: ReportedPost, reply: String) = runAdmin("Raportările au fost respinse.") {
        val admin = userRepository.currentUserOnce() ?: return@runAdmin "Sesiune expirată."
        val text = reply.trim().ifBlank { DEFAULT_REJECTED_REPLY }
        postRepository.rejectReports(reported.post.id, admin.email, text)
        null
    }

    fun toggleRole(user: UserEntity) {
        val newRole = if (user.role == UserRole.ADMIN) UserRole.USER else UserRole.ADMIN
        runAdmin(
            if (newRole == UserRole.ADMIN) "${user.email} este acum administrator."
            else "${user.email} este acum utilizator."
        ) { userRepository.changeRole(user.email, newRole) }
    }

    fun toggleBlocked(user: UserEntity) = runAdmin(
        if (user.isBlocked) "${user.email} a fost deblocat." else "${user.email} a fost blocat."
    ) { userRepository.setBlocked(user.email, !user.isBlocked) }

    companion object {
        const val DEFAULT_RESOLVED_REPLY = "Mulțumim! Postarea încălca regulile și a fost eliminată."
        const val DEFAULT_REJECTED_REPLY = "Am verificat postarea și nu încalcă regulile comunității."
        const val DEFAULT_AUTHOR_MESSAGE =
            "Postarea ta a fost ștearsă de un administrator deoarece a fost raportată și încălca regulile comunității."

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
                AdminViewModel(app.container.postRepository, app.container.roleRepository)
            }
        }
    }
}
