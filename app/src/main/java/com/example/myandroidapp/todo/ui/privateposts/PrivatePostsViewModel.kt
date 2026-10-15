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
            // Vizitatorii (Google / Apple / anonim) nu au cont, deci nu au sectiune privata
            if (userPreferencesRepository.userPreferencesStream.first().token.isEmpty()) {
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
        if (email.isBlank() || password.isBlank()) {
            errorMessage = "Introduceți e-mailul și parola."
            return
        }
        viewModelScope.launch {
            verifying = true
            errorMessage = null
            val prefs = userPreferencesRepository.userPreferencesStream.first()
            val ok = when {
                prefs.token.isEmpty() -> {
                    errorMessage = NO_ACCOUNT_MESSAGE
                    false
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
            "Secțiunea privată este disponibilă doar utilizatorilor conectați cu e-mail și parolă."

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
