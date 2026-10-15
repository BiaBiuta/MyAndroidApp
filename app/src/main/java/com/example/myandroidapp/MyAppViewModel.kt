package com.example.myandroidapp

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.core.data.UserPreferences
import com.example.myandroidapp.core.data.UserPreferencesRepository
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.todo.data.PostRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Starea sesiunii:
 *  - Loading   : inca citim DataStore-ul
 *  - LoggedOut : nu exista token -> doar login cu e-mail si parola
 *  - Locked    : exista token + amprenta activata, dar inca nu s-a deblocat in aceasta deschidere
 *  - Active    : sesiune deblocata, se poate intra in ecranul principal
 */
sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data object Locked : SessionState
    data class Active(val token: String) : SessionState
}

class MyAppViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val postRepository: PostRepository
) : ViewModel() {

    // Traieste cat traieste Activity-ul: la inchiderea si redeschiderea aplicatiei
    // ViewModel-ul e nou -> unlocked = false -> se cere din nou amprenta.
    private val unlocked = MutableStateFlow(false)

    val sessionState: StateFlow<SessionState> =
        combine(userPreferencesRepository.userPreferencesStream, unlocked) { prefs, isUnlocked ->
            when {
                prefs.token.isEmpty() -> SessionState.LoggedOut
                prefs.biometricEnabled && !isUnlocked -> SessionState.Locked
                else -> SessionState.Active(prefs.token)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Loading)

    init {
        Log.d(TAG, "init")
    }

    /** Apelat dupa login cu e-mail/parola sau dupa amprenta acceptata. */
    fun unlock() {
        unlocked.value = true
    }

    // Momentul in care aplicatia a trecut in fundal (Home / alta aplicatie)
    private var backgroundedAt: Long? = null

    fun onAppBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    /** La revenirea din fundal, daca a stat destul de mult acolo, sesiunea se blocheaza din nou. */
    fun onAppForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (SystemClock.elapsedRealtime() - since >= LOCK_AFTER_MS) {
            unlocked.value = false
        }
    }

    /** Sterge complet sesiunea si token-ul, apoi apeleaza onLoggedOut (navigare la login). */
    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            // token, user si flag-ul biometric sunt sterse (UserPreferences() = valori goale)
            userPreferencesRepository.save(UserPreferences())
            Api.tokenInterceptor.token = null
            unlocked.value = false
            try {
                postRepository.deleteAll()
            } catch (e: Exception) {
                Log.w(TAG, "deleteAll failed", e)
            }
            onLoggedOut()
        }
    }

    fun setToken(token: String) {
        postRepository.setToken(token)
    }

    companion object {
        // cat timp poate sta aplicatia in fundal fara sa ceara din nou amprenta
        const val LOCK_AFTER_MS = 30_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
                MyAppViewModel(
                    app.container.userPreferencesRepository,
                    app.container.postRepository
                )
            }
        }
    }
}
