package com.example.myandroidapp.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myandroidapp.R

fun Context.findFragmentActivity(): FragmentActivity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is FragmentActivity) {
            return currentContext
        }
        currentContext = currentContext.baseContext
    }
    return null
}

/** Ecranul de sistem pentru adaugarea unei amprente, in functie de versiunea Android. */
private fun biometricEnrollIntent(): Intent = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
        Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
            Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
        @Suppress("DEPRECATION") Intent(Settings.ACTION_FINGERPRINT_ENROLL)
    else -> Intent(Settings.ACTION_SECURITY_SETTINGS)
}

/** Latimea maxima a formularului: pe tableta / landscape nu se intinde pe tot ecranul. */
private val MaxFormWidth = 480.dp
private val ButtonHeight = 48.dp

/**
 * @param sessionLocked exista o sesiune salvata (token) cu amprenta activata, care asteapta
 *        deblocarea. Doar atunci amprenta poate deschide aplicatia.
 * @param onSessionUnlocked login cu parola reusit sau amprenta acceptata -> sesiunea e deblocata;
 *        MyAppNavHost navigheaza in ecranul principal cand sesiunea devine activa.
 * @param onEnterWithoutSession intrare demo fara token (Google / Apple / anonim).
 */
@Composable
fun LoginScreen(
    sessionLocked: Boolean,
    onSessionUnlocked: () -> Unit,
    onEnterWithoutSession: () -> Unit,
    viewModel: LoginViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val emailFocus = remember { FocusRequester() }
    var passwordVisible by remember { mutableStateOf(false) }

    // Starea amprentei, afisata in ecran (nu doar Toast), ca sa fie citita si de TalkBack
    var biometricStatus by remember { mutableStateOf<String?>(null) }
    var biometricStatusIsError by remember { mutableStateOf(false) }
    fun showBiometricStatus(message: String, isError: Boolean) {
        biometricStatus = message
        biometricStatusIsError = isError
    }

    // Cand revine din Setari (adaugare amprenta), verificam daca si-a adaugat-o
    val enrollLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onEnrollmentFinished(onSessionUnlocked)
    }

    fun launchNativeBiometricPrompt() {
        // Amprenta deblocheaza doar o sesiune salvata (butonul e vizibil doar atunci)
        if (!sessionLocked) return

        val activity = context.findFragmentActivity()
        if (activity == null) {
            showBiometricStatus(context.getString(R.string.bio_no_activity), true)
            return
        }

        when (BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                showBiometricStatus(context.getString(R.string.bio_no_hardware), true); return
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                showBiometricStatus(context.getString(R.string.bio_none_enrolled), true); return
            }
            else -> {
                showBiometricStatus(context.getString(R.string.bio_hw_unavailable), true); return
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.bio_prompt_title))
            .setSubtitle(context.getString(R.string.bio_prompt_subtitle))
            .setNegativeButtonText(context.getString(R.string.bio_prompt_fallback))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        val biometricPrompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    showBiometricStatus(context.getString(R.string.bio_success), false)
                    onSessionUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    when (errorCode) {
                        // A ales "Folosește e-mail și parola" -> mutam focusul pe formular
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED -> {
                            showBiometricStatus(context.getString(R.string.bio_fallback_hint), false)
                            emailFocus.requestFocus()
                        }
                        BiometricPrompt.ERROR_CANCELED -> Unit
                        // ex. prea multe incercari (lockout): mesajul sistemului + fallback la parola
                        else -> {
                            showBiometricStatus(
                                "$errString. " + context.getString(R.string.bio_fallback_hint),
                                true
                            )
                            emailFocus.requestFocus()
                        }
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    showBiometricStatus(context.getString(R.string.bio_failed), true)
                }
            }
        )

        biometricPrompt.authenticate(promptInfo)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = MaxFormWidth)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AuthHeader(isLoginMode = state.isLoginMode)

                Spacer(modifier = Modifier.height(20.dp))

                // Sesiune salvata cu amprenta activata -> card de deblocare
                AnimatedVisibility(visible = sessionLocked && state.isLoginMode) {
                    BiometricUnlockCard(
                        status = biometricStatus,
                        statusIsError = biometricStatusIsError,
                        onUnlock = { launchNativeBiometricPrompt() }
                    )
                }

                TabRow(
                    selectedTabIndex = if (state.isLoginMode) 0 else 1,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = state.isLoginMode,
                        onClick = { if (!state.isLoginMode) viewModel.toggleAuthMode() },
                        enabled = !state.isAuthenticating,
                        text = { Text(stringResource(R.string.auth_tab_login), fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = !state.isLoginMode,
                        onClick = { if (state.isLoginMode) viewModel.toggleAuthMode() },
                        enabled = !state.isAuthenticating,
                        text = { Text(stringResource(R.string.auth_tab_register), fontWeight = FontWeight.SemiBold) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                AnimatedVisibility(visible = state.generalErrorMessage != null) {
                    ErrorBanner(message = state.generalErrorMessage ?: "")
                }

                OutlinedTextField(
                    value = state.email,
                    onValueChange = viewModel::onEmailChanged,
                    label = { Text(stringResource(R.string.auth_email_label)) },
                    placeholder = { Text(stringResource(R.string.auth_email_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                    isError = state.emailError != null,
                    supportingText = { state.emailError?.let { FieldError(it) } },
                    singleLine = true,
                    enabled = !state.isAuthenticating,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(emailFocus),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChanged,
                    label = { Text(stringResource(R.string.auth_password_label)) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.auth_hide_password else R.string.auth_show_password
                                )
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    isError = state.passwordError != null,
                    supportingText = {
                        val error = state.passwordError
                        when {
                            error != null -> FieldError(error)
                            !state.isLoginMode && state.password.isEmpty() ->
                                Text(stringResource(R.string.auth_password_hint))
                        }
                    },
                    singleLine = true,
                    enabled = !state.isAuthenticating,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (state.isLoginMode) ImeAction.Done else ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                        onDone = {
                            focusManager.clearFocus()
                            viewModel.submitEmailAuth(onSessionUnlocked, onEnterWithoutSession)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Doar la Inregistrare: puterea parolei + lista de reguli, actualizate la fiecare tasta
                AnimatedVisibility(visible = state.passwordCheck != null) {
                    state.passwordCheck?.let { PasswordStrengthPanel(it) }
                }

                AnimatedVisibility(visible = !state.isLoginMode) {
                    Column {
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = state.confirmPassword,
                            onValueChange = viewModel::onConfirmPasswordChanged,
                            label = { Text(stringResource(R.string.auth_confirm_password_label)) },
                            leadingIcon = { Icon(Icons.Default.LockReset, contentDescription = null) },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            isError = state.confirmPasswordError != null,
                            supportingText = { state.confirmPasswordError?.let { FieldError(it) } },
                            singleLine = true,
                            enabled = !state.isAuthenticating,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    viewModel.submitEmailAuth(onSessionUnlocked, onEnterWithoutSession)
                                }
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Butonul principal (call to action) — Material 3 filled button
                Button(
                    onClick = {
                        if (!state.isAuthenticating) {
                            focusManager.clearFocus()
                            viewModel.submitEmailAuth(onSessionUnlocked, onEnterWithoutSession)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ButtonHeight),
                    // butonul aflat in incarcare ramane in culorile normale (contrast bun);
                    // dupa prea multe incercari gresite e blocat pana expira timpul
                    enabled = state.lockoutSecondsLeft == 0 &&
                            (!state.isAuthenticating || state.activeMethod == AuthMethod.EMAIL)
                ) {
                    if (state.lockoutSecondsLeft > 0) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.auth_locked_button, state.lockoutSecondsLeft),
                            fontSize = 16.sp
                        )
                    } else if (state.activeMethod == AuthMethod.EMAIL) {
                        ButtonSpinner(color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.auth_loading_email), fontSize = 16.sp)
                    } else {
                        Text(
                            text = stringResource(
                                if (state.isLoginMode) R.string.auth_submit_login else R.string.auth_submit_register
                            ),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                OrDivider()
                Spacer(modifier = Modifier.height(16.dp))

                GoogleSignInButton(
                    loading = state.activeMethod == AuthMethod.GOOGLE,
                    enabled = !state.isAuthenticating,
                    onClick = { viewModel.signInWithProvider(AuthMethod.GOOGLE, onEnterWithoutSession) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                AppleSignInButton(
                    loading = state.activeMethod == AuthMethod.APPLE,
                    enabled = !state.isAuthenticating,
                    onClick = { viewModel.signInWithProvider(AuthMethod.APPLE, onEnterWithoutSession) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = { if (!state.isAuthenticating) viewModel.signInAnonymously(onEnterWithoutSession) },
                    enabled = !state.isAuthenticating || state.activeMethod == AuthMethod.ANONYMOUS,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ButtonHeight)
                ) {
                    if (state.activeMethod == AuthMethod.ANONYMOUS) {
                        ButtonSpinner(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.auth_loading_provider))
                    } else {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.auth_anonymous))
                    }
                }

                Text(
                    text = stringResource(R.string.auth_provider_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    // La redeschiderea aplicatiei (sesiune salvata + amprenta activata) se ridica automat
    // sheet-ul nativ de amprenta.
    LaunchedEffect(sessionLocked) {
        if (sessionLocked) {
            launchNativeBiometricPrompt()
        } else {
            biometricStatus = null
        }
    }

    if (state.showBiometricOnboardingPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.setBiometricsEnabled(false, onSessionUnlocked) },
            icon = {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = { Text(stringResource(R.string.bio_onboarding_title), textAlign = TextAlign.Center) },
            text = {
                Text(
                    stringResource(
                        if (state.biometricNeedsEnrollment) R.string.bio_onboarding_enroll_body
                        else R.string.bio_onboarding_body
                    )
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (state.biometricNeedsEnrollment) {
                        try {
                            enrollLauncher.launch(biometricEnrollIntent())
                        } catch (e: ActivityNotFoundException) {
                            enrollLauncher.launch(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        }
                    } else {
                        viewModel.setBiometricsEnabled(true, onSessionUnlocked)
                    }
                }) {
                    Text(
                        stringResource(
                            if (state.biometricNeedsEnrollment) R.string.bio_onboarding_enroll
                            else R.string.bio_onboarding_enable
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setBiometricsEnabled(false, onSessionUnlocked) }) {
                    Text(stringResource(R.string.bio_onboarding_later))
                }
            }
        )
    }
}

@Composable
private fun AuthHeader(isLoginMode: Boolean) {
    Icon(
        imageVector = Icons.Default.Place,
        contentDescription = stringResource(R.string.auth_logo_description),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(64.dp)
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.auth_brand),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(if (isLoginMode) R.string.auth_subtitle_login else R.string.auth_subtitle_register),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

/** Cardul afisat cand exista o sesiune blocata: buton de amprenta + starea ultimei incercari. */
@Composable
private fun BiometricUnlockCard(
    status: String?,
    statusIsError: Boolean,
    onUnlock: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.bio_locked_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        text = stringResource(R.string.bio_locked_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            if (status != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (statusIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onUnlock,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ButtonHeight)
            ) {
                Icon(Icons.Default.Fingerprint, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.bio_unlock_button), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Puterea parolei noi si lista de reguli (Inregistrare). Fiecare regula are iconita SI text
 * ("Îndeplinit" / "Neîndeplinit" pentru TalkBack), deci starea nu e transmisa doar prin culoare.
 */
@Composable
private fun PasswordStrengthPanel(check: PasswordPolicy.Result) {
    val strength = check.strength
    val strengthColor = when (strength) {
        PasswordPolicy.Strength.WEAK -> MaterialTheme.colorScheme.error
        PasswordPolicy.Strength.MEDIUM -> MaterialTheme.colorScheme.tertiary
        PasswordPolicy.Strength.STRONG,
        PasswordPolicy.Strength.VERY_STRONG -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            LinearProgressIndicator(
                progress = { strength.progress },
                color = strengthColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.auth_password_strength, strength.label),
                style = MaterialTheme.typography.labelLarge,
                color = strengthColor
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        PasswordPolicy.Rule.entries.forEach { rule ->
            val ok = rule in check.passed
            val status = stringResource(if (ok) R.string.auth_rule_met else R.string.auth_rule_unmet)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "$status: ${rule.label}" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = rule.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            // TalkBack anunta eroarea imediat ce apare
            .semantics { liveRegion = LiveRegionMode.Assertive }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = stringResource(R.string.auth_error_icon),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Mesajul de sub un camp invalid, anuntat de TalkBack cand se schimba. */
@Composable
private fun FieldError(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
private fun OrDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = stringResource(R.string.auth_or),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ButtonSpinner(color: Color) {
    CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        color = color,
        strokeWidth = 2.dp
    )
}

/**
 * Butonul "Continuați cu Google", dupa Google Identity branding guidelines:
 * logo-ul "G" oficial (necolorat peste), fundal alb #FFFFFF cu bordura #747775 si text #1F1F1F
 * in tema luminoasa; fundal #131314, bordura #8E918F, text #E3E3E3 in tema intunecata;
 * font Roboto Medium, forma "pill".
 */
@Composable
private fun GoogleSignInButton(loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val container = if (dark) Color(0xFF131314) else Color(0xFFFFFFFF)
    val border = if (dark) Color(0xFF8E918F) else Color(0xFF747775)
    val content = if (dark) Color(0xFFE3E3E3) else Color(0xFF1F1F1F)

    OutlinedButton(
        onClick = { if (!loading) onClick() },
        enabled = enabled || loading,
        modifier = Modifier
            .fillMaxWidth()
            .height(ButtonHeight),
        border = BorderStroke(1.dp, border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.38f),
            disabledContentColor = content.copy(alpha = 0.38f)
        ),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        if (loading) {
            ButtonSpinner(color = content)
        } else {
            // Logo-ul oficial, multicolor: Image (nu Icon), ca sa nu fie recolorat
            Image(
                painter = painterResource(R.drawable.ic_google_logo),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(if (loading) R.string.auth_loading_provider else R.string.auth_google),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Butonul "Continuați cu Apple", dupa Apple Human Interface Guidelines (Sign in with Apple):
 * stilul negru cu logo si text alb in tema luminoasa, stilul alb cu logo si text negru in tema
 * intunecata; logo-ul Apple oficial, la aceeasi inaltime cu textul.
 */
@Composable
private fun AppleSignInButton(loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val container = if (dark) Color.White else Color.Black
    val content = if (dark) Color.Black else Color.White

    Button(
        onClick = { if (!loading) onClick() },
        enabled = enabled || loading,
        modifier = Modifier
            .fillMaxWidth()
            .height(ButtonHeight),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.38f),
            disabledContentColor = content.copy(alpha = 0.38f)
        )
    ) {
        if (loading) {
            ButtonSpinner(color = content)
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_apple_logo),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(if (loading) R.string.auth_loading_provider else R.string.auth_apple),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
