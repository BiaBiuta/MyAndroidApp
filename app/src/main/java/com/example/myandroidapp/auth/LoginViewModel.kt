package com.example.myandroidapp.auth

import android.app.Application
import com.example.myandroidapp.core.logging.SafeLog
import androidx.biometric.BiometricManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.auth.data.AuthRepository
import com.example.myandroidapp.auth.data.local.AuthProvider
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.core.data.UserPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Metoda de autentificare aflata in curs -> doar butonul ei arata indicatorul de incarcare. */
enum class AuthMethod(val provider: String) {
    EMAIL(AuthProvider.EMAIL),
    GOOGLE(AuthProvider.GOOGLE),
    APPLE(AuthProvider.APPLE),
    ANONYMOUS(AuthProvider.ANONYMOUS)
}

data class LoginUiState(
    val isLoginMode: Boolean = true,
    val email: String = "",
    val emailError: String? = null,
    val password: String = "",
    val passwordError: String? = null,
    val confirmPassword: String = "",
    val confirmPasswordError: String? = null,
    // doar la Inregistrare: ce reguli indeplineste parola noua (lista + indicatorul de putere)
    val passwordCheck: PasswordPolicy.Result? = null,
    // login blocat temporar dupa prea multe incercari gresite (secunde ramase)
    val lockoutSecondsLeft: Int = 0,
    // selectorul de cont Google / Apple (simulat): deschis pentru metoda data
    val providerPicker: AuthMethod? = null,
    val knownProviderAccounts: List<String> = emptyList(),
    val providerEmail: String = "",
    val providerEmailError: String? = null,
    val isAuthenticating: Boolean = false,
    val activeMethod: AuthMethod? = null,
    val authenticationError: Throwable? = null,
    val generalErrorMessage: String? = null,
    val isSuccess: Boolean = false,
    // dialogul "Activează autentificarea biometrică" de dupa login
    val showBiometricOnboardingPrompt: Boolean = false,
    // telefonul are senzor, dar nicio amprenta inregistrata -> trimitem utilizatorul sa o adauge
    val biometricNeedsEnrollment: Boolean = false
)

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as MyApplication).container
    private val authRepository = container.authRepository
    private val userPreferencesRepository = container.userPreferencesRepository
    private val roleRepository = container.roleRepository
    private val biometricChoices = BiometricChoiceStore(application)
    private val loginThrottle = LoginThrottle(application)
    private val guestIdentity = GuestIdentity(application)
    private var lockoutJob: Job? = null

    // Sesiunea (token + user) primita la login. O salvam abia dupa ce utilizatorul
    // raspunde la dialogul de activare a amprentei, impreuna cu alegerea lui.
    private var pendingSession: UserPreferences? = null

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private fun biometricStatus(): Int =
        BiometricManager.from(getApplication())
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

    private fun biometricAvailable(): Boolean =
        biometricStatus() == BiometricManager.BIOMETRIC_SUCCESS

    /** Are senzor (cu sau fara amprenta inregistrata) */
    private fun biometricHardwarePresent(): Boolean =
        biometricStatus() == BiometricManager.BIOMETRIC_SUCCESS ||
                biometricStatus() == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

    fun toggleAuthMode() {
        _uiState.update {
            it.copy(
                isLoginMode = !it.isLoginMode,
                generalErrorMessage = null,
                emailError = null,
                passwordError = null,
                confirmPasswordError = null,
                // la Inregistrare verificam parola deja scrisa; la Autentificare nu afisam reguli
                passwordCheck = if (it.isLoginMode && it.password.isNotEmpty())
                    PasswordPolicy.check(it.password, it.email) else null
            )
        }
    }

    fun onEmailChanged(email: String) {
        val trimmed = email.trim()   // spatiile de la autocompletare nu fac adresa invalida
        val error = EmailValidator.error(trimmed)
        _uiState.update {
            it.copy(
                email = trimmed,
                emailError = error,
                generalErrorMessage = null,
                // regula "nu contine numele din e-mail" depinde si de adresa
                passwordCheck = if (!it.isLoginMode && it.password.isNotEmpty())
                    PasswordPolicy.check(it.password, trimmed) else null
            )
        }
    }

    fun onPasswordChanged(password: String) {
        val state = _uiState.value
        val check = if (!state.isLoginMode && password.isNotEmpty())
            PasswordPolicy.check(password, state.email) else null
        val error = when {
            password.isEmpty() -> null
            password.length > PasswordPolicy.MAX_LENGTH ->
                "Parola poate avea cel mult ${PasswordPolicy.MAX_LENGTH} de caractere"
            // la Inregistrare regulile se vad in timp real in lista de sub camp;
            // la Autentificare nu aratam regulile (nu ajutam un atacator si nu blocam conturile vechi)
            else -> null
        }
        _uiState.update {
            it.copy(
                password = password,
                passwordError = error,
                passwordCheck = check,
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
     * @param onEnterWithoutSession nefolosit aici (inregistrarea creeaza acum un cont real); pastrat pentru LoginScreen.
     */
    fun submitEmailAuth(onSessionUnlocked: () -> Unit, onEnterWithoutSession: () -> Unit) {
        val state = _uiState.value
        var hasError = false

        val emailErr = if (state.email.isBlank()) {
            hasError = true
            "Introduceți adresa de e-mail"
        } else state.emailError

        val passErr = when {
            state.password.isBlank() -> {
                hasError = true
                "Introduceți parola"
            }
            !state.isLoginMode && state.passwordCheck?.isValid != true -> {
                hasError = true
                "Parola nu îndeplinește toate cerințele de mai jos"
            }
            else -> state.passwordError
        }

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

        // Prea multe incercari gresite pentru acest cont -> login blocat temporar
        if (state.isLoginMode && loginThrottle.remainingLockMs(state.email) > 0) {
            startLockoutCountdown(state.email)
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isAuthenticating = true, activeMethod = AuthMethod.EMAIL, generalErrorMessage = null)
            }

            // Repository-ul decide: server (token JWT) sau, daca serverul nu raspunde, baza locala
            val result = try {
                if (state.isLoginMode) authRepository.login(state.email, state.password)
                else authRepository.register(state.email, state.password)
            } catch (e: Exception) {
                SafeLog.w(TAG, "auth failed", e)
                AuthRepository.AuthResult.Failure(
                    "A apărut o eroare neașteptată. Încercați din nou.",
                    AuthRepository.Reason.REJECTED
                )
            }

            val session = when (result) {
                is AuthRepository.AuthResult.Success -> result.session
                is AuthRepository.AuthResult.Failure -> {
                    onEmailAuthFailed(state, result)
                    return@launch
                }
            }
            if (state.isLoginMode) {
                loginThrottle.registerSuccess(state.email)
            } else {
                // cont NOU (chiar daca adresa a mai fost folosita pe telefon): dialogul de amprenta
                // trebuie sa apara din nou, nu se reaplica alegerea unui cont vechi
                biometricChoices.forget(session.email)
            }
            // dupa login reusit, parola nu mai ramane in starea ecranului
            _uiState.update {
                it.copy(
                    isAuthenticating = false,
                    activeMethod = null,
                    password = "",
                    confirmPassword = "",
                    passwordCheck = null
                )
            }

            finishSignIn(session, onSessionUnlocked)
        }
    }

    /**
     * Login / inregistrare esuate: mesajul pentru utilizator si, la parola gresita,
     * numaratoarea incercarilor (blocare temporara dupa prea multe, si pe telefon, si pe server).
     */
    private fun onEmailAuthFailed(state: LoginUiState, failure: AuthRepository.AuthResult.Failure) {
        var message = failure.message
        when (failure.reason) {
            AuthRepository.Reason.LOCKED -> {
                // serverul a blocat temporar contul (429): numaratoare inversa cu timpul primit
                _uiState.update { it.copy(isAuthenticating = false, activeMethod = null, password = "") }
                startCountdown((failure.retryAfterSeconds ?: 30) * 1000L) { failure.message }
                return
            }
            AuthRepository.Reason.INVALID_CREDENTIALS -> if (state.isLoginMode) {
                val lockMs = loginThrottle.registerFailure(state.email)
                if (lockMs > 0) {
                    _uiState.update { it.copy(isAuthenticating = false, activeMethod = null, password = "") }
                    startLockoutCountdown(state.email)
                    return
                }
                val left = loginThrottle.attemptsLeft(state.email)
                if (left <= 2) {
                    message = "$INVALID_CREDENTIALS " +
                            if (left == 1) "Mai ai o singură încercare." else "Mai ai $left încercări."
                }
            }
            else -> Unit
        }
        _uiState.update {
            it.copy(
                isAuthenticating = false,
                activeMethod = null,
                generalErrorMessage = message,
                // parola gresita nu ramane in camp (si nici in memorie)
                password = if (it.isLoginMode) "" else it.password
            )
        }
    }

    // ---------- Google / Apple / anonim ----------
    // Tema nu cere integrare reala cu Google / Apple: selectorul de cont e simulat, dar contul
    // ales se creeaza / se incarca din baza de date locala, cu rolul si datele lui, exact ca la e-mail.

    /** Deschide selectorul de cont (simulat) pentru Google sau Apple. */
    fun openProviderPicker(method: AuthMethod) {
        viewModelScope.launch {
            val accounts = roleRepository.accountsFor(method.provider).map { it.email }
            _uiState.update {
                it.copy(
                    providerPicker = method,
                    knownProviderAccounts = accounts,
                    providerEmail = "",
                    providerEmailError = null,
                    generalErrorMessage = null
                )
            }
        }
    }

    fun dismissProviderPicker() {
        _uiState.update { it.copy(providerPicker = null, providerEmailError = null) }
    }

    fun onProviderEmailChanged(email: String) {
        val trimmed = email.trim()
        _uiState.update {
            it.copy(
                providerEmail = trimmed,
                providerEmailError = EmailValidator.error(trimmed)
            )
        }
    }

    /** Contul ales in selector: il incarcam / il cream in baza locala si deschidem sesiunea. */
    fun confirmProviderAccount(email: String, onSessionUnlocked: () -> Unit) {
        val method = _uiState.value.providerPicker ?: return
        val trimmed = email.trim()
        if (!EmailValidator.isValid(trimmed)) {
            _uiState.update { it.copy(providerEmailError = "Introduceți o adresă de e-mail validă") }
            return
        }
        _uiState.update { it.copy(providerPicker = null) }
        signInLocally(method, trimmed, onSessionUnlocked)
    }

    /** Vizitator anonim: acelasi cont de vizitator (server + baza locala) pe acest telefon. */
    fun signInAnonymously(onSessionUnlocked: () -> Unit) =
        signInLocally(AuthMethod.ANONYMOUS, guestIdentity.id(), onSessionUnlocked)

    /**
     * Google / Apple / anonim: AuthRepository trece prin server (token JWT) sau, offline,
     * prin contul deja salvat pe telefon. [value] = adresa contului ales sau id-ul vizitatorului.
     */
    private fun signInLocally(method: AuthMethod, value: String, onSessionUnlocked: () -> Unit) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isAuthenticating = true, activeMethod = method, generalErrorMessage = null)
            }
            val result = try {
                if (method == AuthMethod.ANONYMOUS) authRepository.signInAnonymously(value)
                else authRepository.signInWithProvider(method.provider, value)
            } catch (e: Exception) {
                SafeLog.w(TAG, "provider sign-in failed", e)
                AuthRepository.AuthResult.Failure(
                    "Conectarea nu a reușit. Încercați din nou.",
                    AuthRepository.Reason.REJECTED
                )
            }
            _uiState.update { it.copy(isAuthenticating = false, activeMethod = null) }
            when (result) {
                is AuthRepository.AuthResult.Success -> finishSignIn(result.session, onSessionUnlocked)
                is AuthRepository.AuthResult.Failure ->
                    _uiState.update { it.copy(generalErrorMessage = result.message) }
            }
        }
    }

    /**
     * Pasul comun dupa ORICE login reusit (e-mail, Google, Apple, anonim):
     *  - vizitatorul anonim intra direct (fara amprenta);
     *  - prima conectare a unui cont -> dialogul "Activează autentificarea biometrică";
     *  - cont cunoscut -> se reaplica alegerea salvata pentru amprenta.
     * Sesiunea se salveaza in DataStore (token-ul criptat), iar MyAppNavHost intra in aplicatie.
     */
    private suspend fun finishSignIn(session: UserPreferences, onSessionUnlocked: () -> Unit) {
        if (session.provider == AuthProvider.ANONYMOUS) {
            onSessionUnlocked()
            userPreferencesRepository.save(session)
            return
        }
        val previousChoice = biometricChoices.choiceFor(session.email)
        if (biometricHardwarePresent() && previousChoice == null) {
            // Prima conectare a acestui cont -> intrebam o singura data daca activeaza amprenta
            pendingSession = session
            _uiState.update {
                it.copy(
                    showBiometricOnboardingPrompt = true,
                    biometricNeedsEnrollment = !biometricAvailable()
                )
            }
        } else {
            // Cont cunoscut (ex. fallback cu parola dupa ce amprenta a esuat):
            // reaplicam alegerea salvata, fara sa mai afisam dialogul.
            onSessionUnlocked()
            userPreferencesRepository.save(
                session.withBiometric(previousChoice == true && biometricAvailable())
            )
        }
    }

    /** Raspunsul la dialogul "Activează autentificarea biometrică". */
    fun setBiometricsEnabled(enable: Boolean, onSessionUnlocked: () -> Unit) {
        _uiState.update { it.copy(showBiometricOnboardingPrompt = false) }
        val session = pendingSession ?: return
        pendingSession = null
        // Raspunsul se tine minte -> dialogul nu mai apare la urmatoarele login-uri ale contului
        biometricChoices.record(session.email, enable)
        // Deblocam inainte sa salvam, ca sa nu se ridice imediat sheet-ul de amprenta.
        onSessionUnlocked()
        viewModelScope.launch {
            userPreferencesRepository.save(session.withBiometric(enable))
        }
    }

    private fun UserPreferences.withBiometric(enabled: Boolean) = UserPreferences(
        email = email,
        token = token,
        user = user,
        biometricEnabled = enabled,
        passwordHash = passwordHash,
        provider = provider
    )

    /** Apelat cand utilizatorul revine din ecranul de sistem pentru adaugarea amprentei. */
    fun onEnrollmentFinished(onSessionUnlocked: () -> Unit) {
        if (biometricAvailable()) {
            // a adaugat amprenta -> activam direct
            _uiState.update { it.copy(biometricNeedsEnrollment = false) }
            setBiometricsEnabled(true, onSessionUnlocked)
        } else {
            // nu a adaugat nimic -> dialogul ramane, poate incerca din nou sau alege "Nu acum"
            _uiState.update { it.copy(biometricNeedsEnrollment = true) }
        }
    }

    /**
     * Login blocat temporar: afisam cate secunde mai sunt si deblocam butonul la final.
     * Durata vine din [LoginThrottle], deci repornirea aplicatiei nu scurteaza blocarea.
     */
    private fun startLockoutCountdown(email: String) =
        startCountdown(loginThrottle.remainingLockMs(email)) { seconds ->
            "Prea multe încercări greșite. Din motive de securitate, poți încerca din nou peste $seconds s."
        }

    /** Butonul de login ramane blocat [durationMs], cu mesajul (actualizat la fiecare secunda). */
    private fun startCountdown(durationMs: Long, message: (seconds: Int) -> String) {
        lockoutJob?.cancel()
        lockoutJob = viewModelScope.launch {
            val until = System.currentTimeMillis() + durationMs
            var remainingMs = durationMs
            while (remainingMs > 0) {
                val seconds = ((remainingMs + 999) / 1000).toInt()
                _uiState.update { it.copy(lockoutSecondsLeft = seconds, generalErrorMessage = message(seconds)) }
                delay(1000)
                remainingMs = until - System.currentTimeMillis()
            }
            _uiState.update { it.copy(lockoutSecondsLeft = 0, generalErrorMessage = null) }
        }
    }

    companion object {
        /** Acelasi mesaj pentru cont inexistent si parola gresita (fara user enumeration). */
        const val INVALID_CREDENTIALS = AuthRepository.INVALID_CREDENTIALS
    }
}
