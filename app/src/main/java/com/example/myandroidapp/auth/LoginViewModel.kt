package com.example.myandroidapp.auth

import android.app.Application
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.auth.data.RoleRepository
import com.example.myandroidapp.auth.data.remote.User
import com.example.myandroidapp.core.PasswordHasher
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.core.data.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Metoda de autentificare aflata in curs -> doar butonul ei arata indicatorul de incarcare. */
enum class AuthMethod { EMAIL, GOOGLE, APPLE, ANONYMOUS }

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
        val error = when {
            trimmed.isBlank() -> null
            trimmed.length > 254 -> "Adresa de e-mail este prea lungă"
            !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmed).matches() -> "Format de e-mail invalid"
            else -> null
        }
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

            // Hash-ul parolei (PBKDF2 cu salt): se salveaza in baza de date locala.
            // Parola in clar nu se salveaza niciodata.
            val passwordHash = withContext(Dispatchers.Default) { PasswordHasher.hash(state.password) }

            // Autentificarea NU creeaza conturi; doar Inregistrarea creeaza.
            val (session, failureMessage) = try {
                if (state.isLoginMode) loginWithEmail(state.email, state.password, passwordHash)
                else registerAccount(state.email, passwordHash)
            } catch (e: Exception) {
                Log.w(TAG, "auth failed", e)
                null to "A apărut o eroare neașteptată. Încercați din nou."
            }

            if (session == null) {
                var message = failureMessage
                if (state.isLoginMode && failureMessage == INVALID_CREDENTIALS) {
                    val lockMs = loginThrottle.registerFailure(state.email)
                    if (lockMs > 0) {
                        _uiState.update { it.copy(isAuthenticating = false, activeMethod = null, password = "") }
                        startLockoutCountdown(state.email)
                        return@launch
                    }
                    val left = loginThrottle.attemptsLeft(state.email)
                    if (left <= 2) {
                        message = "$INVALID_CREDENTIALS " +
                                if (left == 1) "Mai ai o singură încercare." else "Mai ai $left încercări."
                    }
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
                return@launch
            }
            if (state.isLoginMode) loginThrottle.registerSuccess(state.email)
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
    }

    /**
     * Autentificare. NU creeaza conturi noi:
     *  1. serverul verifica e-mailul si parola -> sesiune cu token de la server;
     *  2. daca serverul nu raspunde, contul si parola se verifica in baza de date locala
     *     (hash-ul salvat la inregistrare / la login-urile anterioare).
     * Intoarce sesiunea, sau null + mesajul de eroare.
     */
    private suspend fun loginWithEmail(
        email: String,
        password: String,
        passwordHash: String
    ): Pair<UserPreferences?, String> {
        if (roleRepository.isBlocked(email)) return null to RoleRepository.BLOCKED_MESSAGE

        val result = authRepository.login(email, password)
        val tokenHolder = result.getOrNull()
        val error = result.exceptionOrNull()

        if (tokenHolder != null) {
            val session = UserPreferences(
                email = email,
                token = tokenHolder.token,
                user = tokenHolder.user,
                passwordHash = passwordHash
            )
            // contul confirmat de server ajunge si in baza locala, pentru login-ul offline
            roleRepository.saveCredentials(email, session.ownerKey, passwordHash)
            return session to ""
        }

        if (error !is java.io.IOException) {
            // Serverul a raspuns, dar a respins datele (ex. 401)
            Log.w(TAG, "login refused by server", error)
            return null to INVALID_CREDENTIALS
        }

        // Serverul nu poate fi contactat -> verificare in baza de date locala
        return when (roleRepository.checkOffline(email, password)) {
            RoleRepository.OfflineCheck.VALID -> {
                val account = roleRepository.findAccount(email)
                // inlocuim hash-ul vechi cu unul nou (algoritmul si iteratiile curente)
                if (account != null) roleRepository.saveCredentials(email, account.ownerKey, passwordHash)
                UserPreferences(
                    email = email,
                    token = "local-" + java.util.UUID.randomUUID().toString(),
                    // pastram id-ul de pe server (daca exista), ca postarile contului sa ramana ale lui
                    user = User(email, "", account?.ownerKey?.toIntOrNull() ?: 0),
                    passwordHash = passwordHash
                ) to ""
            }
            // Acelasi mesaj pentru cont inexistent si parola gresita: altfel un atacator
            // ar putea afla ce adrese de e-mail au cont in aplicatie (user enumeration).
            RoleRepository.OfflineCheck.UNKNOWN_ACCOUNT,
            RoleRepository.OfflineCheck.WRONG_PASSWORD ->
                null to INVALID_CREDENTIALS
            RoleRepository.OfflineCheck.BLOCKED ->
                null to RoleRepository.BLOCKED_MESSAGE
        }
    }

    /**
     * Inregistrare: contul nou se creeaza in baza de date locala, cu hash-ul parolei.
     * Daca e-mailul are deja cont, inregistrarea e refuzata.
     */
    private suspend fun registerAccount(email: String, passwordHash: String): Pair<UserPreferences?, String> {
        return when (roleRepository.registerAccount(email, passwordHash)) {
            RoleRepository.RegisterResult.ALREADY_EXISTS ->
                null to "Există deja un cont cu acest e-mail. Folosiți tab-ul Autentificare."
            RoleRepository.RegisterResult.BLOCKED ->
                null to RoleRepository.BLOCKED_MESSAGE
            RoleRepository.RegisterResult.CREATED -> {
                val account = roleRepository.findAccount(email)
                UserPreferences(
                    email = email,
                    token = "local-" + java.util.UUID.randomUUID().toString(),
                    user = User(email, "", account?.ownerKey?.toIntOrNull() ?: 0),
                    passwordHash = passwordHash
                ) to ""
            }
        }
    }

    // Google / Apple / anonim sunt demo si nu au token, deci nu creeaza o sesiune salvata:
    // nu pot fi deblocate ulterior cu amprenta si nu au sectiune privata.
    fun signInWithProvider(method: AuthMethod, onEnterWithoutSession: () -> Unit) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isAuthenticating = true, activeMethod = method, generalErrorMessage = null)
            }
            delay(900)
            _uiState.update { it.copy(isAuthenticating = false, activeMethod = null) }
            onEnterWithoutSession()
        }
    }

    fun signInAnonymously(onEnterWithoutSession: () -> Unit) =
        signInWithProvider(AuthMethod.ANONYMOUS, onEnterWithoutSession)

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
        passwordHash = passwordHash
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
    private fun startLockoutCountdown(email: String) {
        lockoutJob?.cancel()
        lockoutJob = viewModelScope.launch {
            var remainingMs = loginThrottle.remainingLockMs(email)
            while (remainingMs > 0) {
                val seconds = ((remainingMs + 999) / 1000).toInt()
                _uiState.update {
                    it.copy(
                        lockoutSecondsLeft = seconds,
                        generalErrorMessage = "Prea multe încercări greșite. Din motive de securitate, " +
                                "poți încerca din nou peste $seconds s."
                    )
                }
                delay(1000)
                remainingMs = loginThrottle.remainingLockMs(email)
            }
            _uiState.update { it.copy(lockoutSecondsLeft = 0, generalErrorMessage = null) }
        }
    }

    companion object {
        /** Acelasi mesaj pentru cont inexistent si parola gresita (fara user enumeration). */
        const val INVALID_CREDENTIALS = "E-mail sau parolă incorecte."
    }
}
