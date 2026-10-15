package com.example.myandroidapp.auth

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val showBiometricOnboardingPrompt: Boolean = false,
    val isBiometricsEnabled: Boolean = false,
    val showBiometricAuthDialog: Boolean = false,
    val biometricErrorMessage: String? = null
)

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        LoginUiState(
            // Citim starea salvată pe disc
            isBiometricsEnabled = prefs.getBoolean("biometrics_enabled", false)
        )
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

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

    fun submitEmailAuth(onSuccess: () -> Unit) {
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
            delay(800)

            if (state.email.contains("error")) {
                _uiState.update {
                    it.copy(
                        isAuthenticating = false,
                        generalErrorMessage = "Date de autentificare incorecte. Verificați e-mailul sau parola."
                    )
                }
            } else {
                _uiState.update { it.copy(isAuthenticating = false) }

                // Dacă nu a fost activată anterior biometria, afișează dialogul de onboarding
                if (!_uiState.value.isBiometricsEnabled) {
                    _uiState.update { it.copy(showBiometricOnboardingPrompt = true) }
                } else {
                    onSuccess()
                }
            }
        }
    }

    fun signInWithProvider(provider: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthenticating = true, generalErrorMessage = null) }
            delay(600)
            _uiState.update { it.copy(isAuthenticating = false) }

            if (!_uiState.value.isBiometricsEnabled) {
                _uiState.update { it.copy(showBiometricOnboardingPrompt = true) }
            } else {
                onSuccess()
            }
        }
    }

    fun signInAnonymously(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthenticating = true, generalErrorMessage = null) }
            delay(400)
            _uiState.update { it.copy(isAuthenticating = false) }
            onSuccess()
        }
    }

    // Apăsarea pe iconița de amprentă de pe ecran deschide direct scanarea
    fun onBiometricIconClicked() {
        _uiState.update {
            it.copy(
                showBiometricAuthDialog = true,
                biometricErrorMessage = null
            )
        }
    }

    // Salvează persistent activarea biometriei după primul login
    fun setBiometricsEnabled(enable: Boolean, onDone: () -> Unit) {
        prefs.edit().putBoolean("biometrics_enabled", enable).apply()
        _uiState.update {
            it.copy(
                isBiometricsEnabled = enable,
                showBiometricOnboardingPrompt = false
            )
        }
        onDone()
    }

    fun onBiometricSuccess(onSuccess: () -> Unit) {
        _uiState.update { it.copy(showBiometricAuthDialog = false) }
        onSuccess()
    }

    fun onBiometricFailed() {
        _uiState.update {
            it.copy(biometricErrorMessage = "Amprentă / Față nerecunoscută. Încercați din nou sau folosiți parola.")
        }
    }

    fun dismissBiometricDialog() {
        _uiState.update { it.copy(showBiometricAuthDialog = false, biometricErrorMessage = null) }
    }
}