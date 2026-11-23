package com.example.myandroidapp

import android.content.Context
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
import com.example.myandroidapp.core.data.remote.SessionEnd
import com.example.myandroidapp.todo.data.PostRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.myandroidapp.auth.data.AuthRepository
import com.example.myandroidapp.auth.data.RoleRepository
import com.example.myandroidapp.auth.data.local.UserEntity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.example.myandroidapp.auth.BiometricChoiceStore
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
    private val postRepository: PostRepository,
    private val roleRepository: RoleRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    /** Contul logat, cu rolul lui (USER / ADMIN), din baza de date locala. Se actualizeaza live. */
    val currentUser: StateFlow<UserEntity?> = roleRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Mesaj afisat utilizatorului (ex. cont blocat de admin)
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun consumeNotice() {
        _notice.value = null
    }

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
        viewModelScope.launch {
            // O sesiune facuta offline intr-o pornire anterioara a aplicatiei nu mai poate trece singura
            // pe server (datele de login sunt tinute doar in memorie): cerem un login nou, ca postarile
            // salvate offline sa poata fi trimise. Postarile din coada raman pe telefon.
            val prefs = userPreferencesRepository.userPreferencesStream.first()
            if (prefs.token.startsWith("local-") && !authRepository.canUpgradeOfflineSession()) {
                _notice.value = "Ultima conectare a fost făcută fără server. Conectează-te din nou, " +
                        "ca postările salvate offline să poată fi trimise."
                logout(revokeOnServer = false) {}
            }
        }
        viewModelScope.launch {
            // Dupa orice login reusit (indiferent de metoda), contul ajunge in baza locala cu rolul lui
            userPreferencesRepository.userPreferencesStream.collect { prefs ->
                try {
                    roleRepository.registerSession(prefs)
                } catch (e: Exception) {
                    Log.w(TAG, "registerSession failed", e)
                }
            }
        }
        viewModelScope.launch {
            // Daca un admin blocheaza contul in timp ce e folosit -> deconectare imediata
            currentUser.collect { user ->
                if (user?.isBlocked == true) {
                    _notice.value = RoleRepository.BLOCKED_MESSAGE
                    logout {}
                }
            }
        }
        viewModelScope.launch {
            // Serverul a respins sesiunea (token expirat / revocat, sau cont blocat) -> login din nou
            Api.tokenInterceptor.sessionEnded.collect { reason ->
                if (userPreferencesRepository.userPreferencesStream.first().token.isNotEmpty()) {
                    _notice.value = when (reason) {
                        SessionEnd.BLOCKED -> RoleRepository.BLOCKED_MESSAGE
                        SessionEnd.EXPIRED -> "Sesiunea a expirat. Conectați-vă din nou."
                    }
                    logout(revokeOnServer = false) {}
                }
            }
        }
    }

    /** Amprenta e activata pentru sesiunea curenta (comutatorul din ecranul principal). */
    val biometricEnabled: StateFlow<Boolean> = userPreferencesRepository.userPreferencesStream
        .map { it.biometricEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Activeaza / dezactiveaza amprenta pentru contul logat. Alegerea se tine minte si
     * pentru login-urile urmatoare (dialogul de dupa login nu mai apare).
     * Merge doar pentru sesiunile cu e-mail si parola (Google / Apple / anonim nu au sesiune salvata).
     */
    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            val prefs = userPreferencesRepository.userPreferencesStream.first()
            if (prefs.token.isEmpty()) return@launch
            BiometricChoiceStore(context).record(prefs.email, enabled)
            userPreferencesRepository.save(
                UserPreferences(
                    email = prefs.email,
                    token = prefs.token,
                    user = prefs.user,
                    biometricEnabled = enabled,
                    passwordHash = prefs.passwordHash,
                    provider=prefs.provider
                )
            )
        }
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

    private var loggingOut = false

    /**
     * Logout: token-ul e revocat pe server (daca e disponibil) si sters de pe telefon,
     * impreuna cu sesiunea salvata; apoi onLoggedOut (navigare la login).
     * [revokeOnServer] = false cand serverul a respins deja token-ul (sesiune expirata).
     */
    fun logout(revokeOnServer: Boolean = true, onLoggedOut: () -> Unit) {
        if (loggingOut) return
        loggingOut = true
        viewModelScope.launch {
            if (revokeOnServer) authRepository.logout() else Api.tokenInterceptor.token = null
            // token, user si flag-ul biometric sunt sterse (UserPreferences() = valori goale)
            userPreferencesRepository.save(UserPreferences())
            postRepository.setToken("")
            unlocked.value = false
            loggingOut = false
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
                    app.container.postRepository,
                    app.container.roleRepository,
                    app.container.authRepository
                )
            }
        }
    }
}
