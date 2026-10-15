package com.example.myandroidapp.auth

import android.app.Application
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.auth.data.remote.User
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.core.data.UserPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val isLoginMode: Boolean = true,
    val email: String = "",
    val emailError: String? = null,
    val password: String = "",
    val passwordError: String? = null,
    val confirmPassword: String = "",
    val confirmPasswordError: String? = null,
    val isAuthenticating: Boolean = false,
    val authenticationError: Throwable? = null,
    val generalErrorMessage: String? = null,
    val isSuccess: Boolean = false,
    // dialogul "Activează autentificarea biometrică" de dupa login
    val showBiometricOnboardingPrompt: Boolean = false
)

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as MyApplication).container
    private val authRepository = container.authRepository
    private val userPreferencesRepository = container.userPreferencesRepository

    // Sesiunea (token + user) primita la login. O salvam abia dupa ce utilizatorul
    // raspunde la dialogul de activare a amprentei, impreuna cu alegerea lui.
    private var pendingSession: UserPreferences? = null

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private fun biometricAvailable(): Boolean =
        BiometricManager.from(getApplication())
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS

    fun toggleAuthMode() {
        _uiState.update {
            it.copy(
                isLoginMode = !it.isLoginMode,
                generalErrorMessage = null,
                emailError = null,
                passwordError = null,
                confirmPasswordError = null
            )
        }
    }

    fun onEmailChanged(email: String) {
        val error = when {
            email.isBlank() -> null
            !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> "Format de e-mail invalid"
            else -> null
        }
        _uiState.update { it.copy(email = email, emailError = error, generalErrorMessage = null) }
    }

    fun onPasswordChanged(password: String) {
        val error = when {
            password.isBlank() -> null
            password.length < 6 -> "Parola trebuie să aibă minim 6 caractere"
            else -> null
        }
        _uiState.update {
            it.copy(
                password = password,
                passwordError = error,
                confirmPasswordError = if (!it.isLoginMode && it.confirmPassword.isNotEmpty() && it.confirmPassword != password) "Parolele nu coincid" else null,
                generalErrorMessage = null
            )
        }
    }

    fun onConfirmPasswordChanged(confirmPass: String) {
        val error = when {
            confirmPass.isBlank() -> null
            confirmPass != _uiState.value.password -> "Parolele nu coincid"
            else -> null
        }
        _uiState.update {
            it.copy(
                confirmPassword = confirmPass,
                confirmPasswordError = error,
                generalErrorMessage = null
            )
        }
    }

    /**
     * @param onSessionUnlocked sesiunea e deblocata (login cu e-mail si parola reusit).
     *        Navigarea o face MyAppNavHost cand sesiunea salvata devine activa.
     * @param onEnterWithoutSession intrare fara sesiune salvata (inregistrare demo).
     */
    fun submitEmailAuth(onSessionUnlocked: () -> Unit, onEnterWithoutSession: () -> Unit) {
        val state = _uiState.value
        var hasError = false

        val emailErr = if (state.email.isBlank()) {
            hasError = true
            "Introduceți adresa de e-mail"
        } else state.emailError

        val passErr = if (state.password.isBlank()) {
            hasError = true
            "Introduceți parola"
        } else state.passwordError

        var confirmPassErr = state.confirmPasswordError
        if (!state.isLoginMode && state.confirmPassword.isBlank()) {
            hasError = true
            confirmPassErr = "Confirmați parola"
        }

        if (hasError || emailErr != null || passErr != null || confirmPassErr != null) {
            _uiState.update {
                it.copy(
                    emailError = emailErr,
                    passwordError = passErr,
                    confirmPasswordError = confirmPassErr,
                    generalErrorMessage = "Vă rugăm să corectați erorile din formular."
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAuthenticating = true, generalErrorMessage = null) }

            if (!state.isLoginMode) {
                // Serverul nu are endpoint de inregistrare: pastram comportamentul demo de dinainte.
                delay(800)
                _uiState.update { it.copy(isAuthenticating = false) }
                onEnterWithoutSession()
                return@launch
            }

            val result = authRepository.login(state.email, state.password)
            val tokenHolder = result.getOrNull()
            val error = result.exceptionOrNull()

            val session: UserPreferences? = when {
                // Serverul a raspuns cu token -> sesiune reala
                tokenHolder != null -> UserPreferences(
                    email = state.email,
                    token = tokenHolder.token,
                    user = tokenHolder.user
                )
                // Serverul nu poate fi contactat (oprit / alt IP) -> sesiune locala demo
                error is java.io.IOException && !state.email.contains("error") -> UserPreferences(
                    email = state.email,
                    token = "local-" + java.util.UUID.randomUUID().toString(),
                    user = User(state.email, "", 0)
                )
                // Serverul a raspuns, dar a respins datele (ex. 401) -> login esuat
                else -> null
            }

            if (session == null) {
                Log.w(TAG, "login failed", error)
                _uiState.update {
                    it.copy(
                        isAuthenticating = false,
                        authenticationError = error,
                        generalErrorMessage = "Date de autentificare incorecte. Verificați e-mailul sau parola."
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(isAuthenticating = false) }

            if (biometricAvailable()) {
                // Prima conectare -> intrebam daca activeaza amprenta
                pendingSession = session
                _uiState.update { it.copy(showBiometricOnboardingPrompt = true) }
            } else {
                onSessionUnlocked()
                userPreferencesRepository.save(session)
            }
        }
    }

    // Google / Apple / anonim sunt demo si nu au token, deci nu creeaza o sesiune salvata:
    // nu pot fi deblocate ulterior cu amprenta.
    fun signInWithProvider(provider: String, onEnterWithoutSession: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthenticating = true, generalErrorMessage = null) }
            delay(600)
            _uiState.update { it.copy(isAuthenticating = false) }
            onEnterWithoutSession()
        }
    }

    fun signInAnonymously(onEnterWithoutSession: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthenticating = true, generalErrorMessage = null) }
            delay(400)
            _uiState.update { it.copy(isAuthenticating = false) }
            onEnterWithoutSession()
        }
    }

    /** Raspunsul la dialogul "Activează autentificarea biometrică". */
    fun setBiometricsEnabled(enable: Boolean, onSessionUnlocked: () -> Unit) {
        _uiState.update { it.copy(showBiometricOnboardingPrompt = false) }
        val session = pendingSession ?: return
        pendingSession = null
        // Deblocam inainte sa salvam, ca sa nu se ridice imediat sheet-ul de amprenta.
        onSessionUnlocked()
        viewModelScope.launch {
            userPreferencesRepository.save(
                UserPreferences(session.email, session.token, session.user, biometricEnabled = enable)
            )
        }
    }
}
