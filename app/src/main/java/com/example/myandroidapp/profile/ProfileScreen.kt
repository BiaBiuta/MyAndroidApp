package com.example.myandroidapp.profile

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.auth.PasswordPolicy
import com.example.myandroidapp.auth.data.RoleRepository
import com.example.myandroidapp.auth.data.local.AuthProvider
import com.example.myandroidapp.auth.data.local.UserEntity
import com.example.myandroidapp.auth.data.local.isAdmin
import com.example.myandroidapp.auth.data.local.isGuest
import com.example.myandroidapp.core.PasswordHasher
import com.example.myandroidapp.core.data.UserPreferences
import com.example.myandroidapp.core.data.UserPreferencesRepository
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.core.data.remote.OfflineException
import com.example.myandroidapp.core.data.remote.apiCall
import com.example.myandroidapp.core.data.remote.toUserMessage
import com.example.myandroidapp.todo.data.PostRepository
import com.example.myandroidapp.todo.data.PostStatus
import com.example.myandroidapp.todo.data.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** Contul, asa cum il stie serverul (GET /me). */
data class Me(
    val id: Int = 0,
    val email: String = "",
    val role: String = "",
    val provider: String = "",
    val createdAt: String = ""
)

data class PasswordChange(val currentPassword: String, val newPassword: String)

interface ProfileService {
    @GET("/me")
    suspend fun me(): Me

    @POST("/me/password")
    suspend fun changePassword(@Body body: PasswordChange): Me
}

/** Statisticile jurnalului utilizatorului, calculate din postarile din cache (merg si offline). */
data class ProfileStats(
    val posts: Int = 0,
    val published: Int = 0,
    val drafts: Int = 0,
    val pending: Int = 0,
    val places: Int = 0,
    val averageRating: Double = 0.0,
    val likesReceived: Int = 0,
    val commentsReceived: Int = 0
)

data class ProfileUi(val user: UserEntity? = null, val stats: ProfileStats = ProfileStats())

class ProfileViewModel(
    postRepository: PostRepository,
    private val roleRepository: RoleRepository,
    private val preferences: UserPreferencesRepository
) : ViewModel() {
    private val service = Api.retrofit.create(ProfileService::class.java)

    val ui: StateFlow<ProfileUi> = combine(postRepository.itemStream, roleRepository.currentUser) { posts, user ->
        val mine = posts.filter { user != null && it.user_id == user.ownerKey && !it.isPrivate }
        val rated = mine.filter { it.rating > 0 }
        ProfileUi(
            user = user,
            stats = ProfileStats(
                posts = mine.size,
                published = mine.count { it.status == PostStatus.ACTIVE },
                drafts = mine.count { it.status == PostStatus.DRAFT },
                pending = mine.count { it.syncStatus != SyncStatus.SYNCED },
                places = mine.map { it.place.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().size,
                averageRating = if (rated.isEmpty()) 0.0 else rated.map { it.rating }.average(),
                likesReceived = mine.sumOf { it.like_count },
                commentsReceived = mine.sumOf { it.comment_count }
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUi())

    var memberSince by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var passwordError by mutableStateOf<String?>(null)
        private set
    var changing by mutableStateOf(false)
        private set
    var passwordChanged by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            memberSince = try {
                formatDay(apiCall { service.me() }.createdAt)
            } catch (e: Exception) {
                // offline: data din contul salvat pe telefon
                roleRepository.currentUserOnce()?.createdAt?.let {
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(it))
                }
            }
        }
    }

    fun consumeMessage() {
        message = null
    }

    /** Schimbarea parolei: pe server (verifica parola actuala), apoi si pe telefon (login offline, sectiunea privata). */
    fun changePassword(current: String, new: String, confirm: String) {
        passwordError = null
        passwordChanged = false
        val user = ui.value.user ?: return
        val check = PasswordPolicy.check(new, user.email)
        when {
            current.isEmpty() -> passwordError = "Introdu parola actuală."
            !check.isValid -> passwordError = "Parola nouă nu respectă regulile: " +
                    PasswordPolicy.Rule.entries.filter { it !in check.passed }.joinToString(", ") { it.label.lowercase() } + "."
            new != confirm -> passwordError = "Parolele noi nu coincid."
            new == current -> passwordError = "Parola nouă trebuie să fie diferită de cea actuală."
            else -> viewModelScope.launch {
                changing = true
                try {
                    apiCall { service.changePassword(PasswordChange(current, new)) }
                    val hash = withContext(Dispatchers.Default) { PasswordHasher.hash(new) }
                    roleRepository.saveCredentials(user.email, user.ownerKey, hash)
                    val prefs = preferences.userPreferencesStream.first()
                    preferences.save(
                        UserPreferences(
                            email = prefs.email,
                            token = prefs.token,
                            user = prefs.user,
                            biometricEnabled = prefs.biometricEnabled,
                            passwordHash = hash,
                            provider = prefs.provider
                        )
                    )
                    passwordChanged = true
                    message = "Parola a fost schimbată."
                } catch (e: Exception) {
                    Log.w("ProfileViewModel", "password change failed", e)
                    passwordError = if (e is OfflineException) "Schimbarea parolei are nevoie de conexiune la server."
                    else e.toUserMessage("Parola nu a putut fi schimbată.")
                }
                changing = false
            }
        }
    }

    private fun formatDay(iso: String): String? = try {
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        parser.parse(iso.take(19))?.let { java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(it) }
    } catch (e: Exception) {
        null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication
                ProfileViewModel(app.container.postRepository, app.container.roleRepository, app.container.userPreferencesRepository)
            }
        }
    }
}

/** Profilul: contul, statisticile jurnalului, schimbarea parolei si deconectarea. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    val viewModel = viewModel<ProfileViewModel>(factory = ProfileViewModel.Factory)
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    val user = ui.user

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profil") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ----- contul -----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            (user?.email?.firstOrNull() ?: '?').uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        if (user?.isGuest == true) "Vizitator" else user?.email?.substringBefore("@").orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (user?.isGuest != true) Text(user?.email.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(
                            if (user?.isAdmin == true) "Administrator" else "Utilizator",
                            user?.provider?.let(AuthProvider::label),
                            viewModel.memberSince?.let { "din $it" }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ----- statistici (doar cine publica) -----
            if (user != null && !user.isAdmin && !user.isGuest) {
                Text("Jurnalul meu", style = MaterialTheme.typography.titleMedium)
                val s = ui.stats
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatCard("Postări", "${s.posts}", "${s.published} publicate · ${s.drafts} ciorne", Modifier.weight(1f))
                    StatCard("Locuri", "${s.places}", "vizitate", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatCard(
                        "Rating mediu",
                        if (s.averageRating > 0) "%.1f ★".format(s.averageRating) else "–",
                        "din postările cu stele",
                        Modifier.weight(1f)
                    )
                    StatCard("Aprecieri", "${s.likesReceived}", "${s.commentsReceived} comentarii primite", Modifier.weight(1f))
                }
                if (s.pending > 0) {
                    Text(
                        "${s.pending} postări așteaptă să fie trimise la server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // ----- parola -----
            if (user?.provider == AuthProvider.EMAIL) {
                HorizontalDivider()
                PasswordSection(viewModel, user.email)
            }

            HorizontalDivider()
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Deconectare")
            }
        }
    }
}

@Composable
private fun PasswordSection(viewModel: ProfileViewModel, email: String) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    LaunchedEffect(viewModel.passwordChanged) {
        if (viewModel.passwordChanged) {
            current = ""; new = ""; confirm = ""
        }
    }
    val check = PasswordPolicy.check(new, email)

    Text("Schimbă parola", style = MaterialTheme.typography.titleMedium)
    PasswordField("Parola actuală", current) { current = it }
    PasswordField("Parola nouă", new) { new = it }
    if (new.isNotEmpty()) {
        Text(
            "Putere: ${check.strength.label}" +
                    PasswordPolicy.Rule.entries.filter { it !in check.passed }
                        .takeIf { it.isNotEmpty() }?.joinToString(prefix = " · lipsește: ") { it.label.lowercase() }.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = if (check.isValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    PasswordField("Confirmă parola nouă", confirm) { confirm = it }
    viewModel.passwordError?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    Button(
        onClick = { viewModel.changePassword(current, new, confirm) },
        enabled = !viewModel.changing && current.isNotEmpty() && new.isNotEmpty() && confirm.isNotEmpty(),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (viewModel.changing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
        }
        Text("Schimbă parola")
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= PasswordPolicy.MAX_LENGTH) onChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun StatCard(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
