package com.example.myandroidapp.todo.ui.admin

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
import com.example.myandroidapp.todo.data.PostRepository
import com.example.myandroidapp.todo.data.local.ReportedPost
import kotlinx.coroutines.flow.Flow
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
    val currentUser: StateFlow<UserEntity?> = userRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

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
                "Acțiunea nu a putut fi finalizată."
            }
            busy = false
        }
    }

    fun deleteReportedPost(reported: ReportedPost) = runAdmin("Postarea a fost eliminată.") {
        val admin = userRepository.currentUserOnce() ?: return@runAdmin "Sesiune expirată."
        postRepository.deletePost(reported.post.id, admin.email)
        null
    }

    fun dismissReports(reported: ReportedPost) = runAdmin("Raportările au fost respinse.") {
        postRepository.dismissReports(reported.post.id)
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
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
                AdminViewModel(app.container.postRepository, app.container.roleRepository)
            }
        }
    }
}
