package com.example.myandroidapp.todo.ui.privateposts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.auth.data.local.AuthProvider
import kotlinx.coroutines.delay
import com.example.myandroidapp.core.PasswordHasher
import com.example.myandroidapp.core.data.UserPreferencesRepository
import com.example.myandroidapp.todo.data.Post
import com.example.myandroidapp.todo.data.PostRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sectiunea privata. ViewModel-ul traieste cat timp ecranul e in back stack:
 * cand utilizatorul iese din sectiune, se distruge, deci la urmatoarea intrare
 * se cere din nou autentificarea (amprenta sau e-mail + parola).
 */
class PrivatePostsViewModel(
    postRepository: PostRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    var unlocked by mutableStateOf(false)
        private set
    var verifying by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /**
     * Metoda de autentificare a sesiunii (AuthProvider). Conturile Google / Apple nu au parola,
     * deci varianta de rezerva e reconfirmarea contului la furnizor (simulata: adresa contului).
     */
    var sessionProvider by mutableStateOf(AuthProvider.EMAIL)
        private set

    val usesPassword: Boolean
        get() = sessionProvider == AuthProvider.EMAIL

    init {
        viewModelScope.launch {
            sessionProvider = userPreferencesRepository.userPreferencesStream.first().provider
        }
    }

    /** Doar postarile private ale utilizatorului logat acum. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val privatePosts: Flow<List<Post>> = userPreferencesRepository.userPreferencesStream
        .map { it.ownerKey }
        .distinctUntilChanged()
        .flatMapLatest { ownerKey ->
            if (ownerKey.isEmpty()) flowOf(emptyList())
            else postRepository.privateItemStream(ownerKey)
        }

    fun onBiometricSuccess() {
        viewModelScope.launch {
            // Vizitatorul anonim (sau nicio sesiune) nu are sectiune privata
            val prefs = userPreferencesRepository.userPreferencesStream.first()
            if (prefs.token.isEmpty() || prefs.provider == AuthProvider.ANONYMOUS) {
                errorMessage = NO_ACCOUNT_MESSAGE
            } else {
                errorMessage = null
                unlocked = true
            }
        }
    }

    fun showError(message: String) {
        errorMessage = message
    }

    /** E-mail-ul trebuie sa fie al sesiunii curente, iar parola sa corespunda hash-ului salvat. */
    fun verifyCredentials(email: String, password: String) {
        if (email.isBlank() || (usesPassword && password.isBlank())) {
            errorMessage = if (usesPassword) "Introduceți e-mailul și parola." else "Introduceți adresa contului."
            return
        }
        viewModelScope.launch {
            verifying = true
            errorMessage = null
            val prefs = userPreferencesRepository.userPreferencesStream.first()
            val ok = when {
                prefs.token.isEmpty() || prefs.provider == AuthProvider.ANONYMOUS -> {
                    errorMessage = NO_ACCOUNT_MESSAGE
                    false
                }
                // Google / Apple: reconfirmarea contului la furnizor (simulata) = aceeasi adresa
                prefs.provider == AuthProvider.GOOGLE || prefs.provider == AuthProvider.APPLE -> {
                    delay(500)
                    val same = email.trim().equals(prefs.email, ignoreCase = true)
                    if (!same) errorMessage = "Contul ${AuthProvider.label(prefs.provider)} nu corespunde sesiunii curente."
                    same
                }
                prefs.passwordHash.isEmpty() -> {
                    errorMessage = "Parola nu poate fi verificată pentru această sesiune. " +
                            "Deconectați-vă și conectați-vă din nou cu e-mail și parolă."
                    false
                }
                !email.trim().equals(prefs.email, ignoreCase = true) -> {
                    errorMessage = "E-mail sau parolă incorecte."
                    false
                }
                else -> {
                    val match = withContext(Dispatchers.Default) {
                        PasswordHasher.verify(password, prefs.passwordHash)
                    }
                    if (!match) errorMessage = "E-mail sau parolă incorecte."
                    match
                }
            }
            verifying = false
            if (ok) unlocked = true
        }
    }

    companion object {
        private const val NO_ACCOUNT_MESSAGE =
            "Secțiunea privată este disponibilă doar utilizatorilor cu cont (nu vizitatorilor anonimi)."

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
                PrivatePostsViewModel(
                    app.container.postRepository,
                    app.container.userPreferencesRepository
                )
            }
        }
    }
}
