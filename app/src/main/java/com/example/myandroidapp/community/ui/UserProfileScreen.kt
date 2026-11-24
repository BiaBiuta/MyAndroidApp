package com.example.myandroidapp.community.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.auth.data.RoleRepository
import com.example.myandroidapp.auth.data.local.UserRole
import com.example.myandroidapp.community.data.CommunityRepository
import com.example.myandroidapp.community.data.PublicProfile
import com.example.myandroidapp.community.data.RemoteTrip
import com.example.myandroidapp.core.data.remote.toUserMessage
import com.example.myandroidapp.todo.data.Post
import com.example.myandroidapp.todo.data.PostRepository
import com.example.myandroidapp.todo.data.PostStatus
import com.example.myandroidapp.todo.ui.post.formatVisitDate
import com.example.myandroidapp.todo.ui.posts.PostPhoto
import com.example.myandroidapp.todo.ui.posts.RatingStars
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UserProfileViewModel(
    postRepository: PostRepository,
    roleRepository: RoleRepository,
    private val community: CommunityRepository
) : ViewModel() {
    private val userId = MutableStateFlow("")

    val currentUser = roleRepository.currentUser.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Postarile publice ale calatorului, din cache (se vad si offline), cele mai noi primele. */
    val posts: StateFlow<List<Post>> = combine(userId, postRepository.itemStream) { id, all ->
        all.filter { it.user_id == id && !it.isPrivate && it.status == PostStatus.ACTIVE && !it.isDeleted }
            .sortedByDescending { it.created_at }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Lista "Urmariti" locala: butonul arata corect si offline. */
    val following: StateFlow<Set<String>> = combine(community.following, userId) { ids, _ -> ids.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    var profile by mutableStateOf<PublicProfile?>(null)
        private set
    var trips by mutableStateOf<List<RemoteTrip>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var offline by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun load(id: String) {
        userId.value = id
        viewModelScope.launch {
            loading = true
            try {
                profile = community.profile(id)
                trips = community.tripsOf(id)
                offline = false
            } catch (e: Exception) {
                offline = true
            }
            loading = false
        }
    }

    fun toggleFollow() {
        val id = userId.value
        val follow = id !in following.value
        viewModelScope.launch {
            busy = true
            try {
                profile = community.setFollowing(id, follow)
                message = if (follow) "Urmărești acest călător. Postările lui apar în „Urmăriți”." else "Nu mai urmărești acest călător."
            } catch (e: Exception) {
                message = e.toUserMessage("Nu s-a putut actualiza. Urmărirea are nevoie de conexiune.")
            }
            busy = false
        }
    }

    fun consumeMessage() {
        message = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication
                UserProfileViewModel(app.container.postRepository, app.container.roleRepository, app.container.communityRepository)
            }
        }
    }
}

/**
 * Profilul public al unui alt calator: avatar, statistici, buton Urmărește,
 * postarile si calatoriile lui. Fara e-mail (serverul nu il trimite).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    userId: String,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenTrip: (tripId: String, ownerId: String) -> Unit
) {
    val viewModel = viewModel<UserProfileViewModel>(factory = UserProfileViewModel.Factory)
    LaunchedEffect(userId) { viewModel.load(userId) }
    val posts by viewModel.posts.collectAsStateWithLifecycle()
    val following by viewModel.following.collectAsStateWithLifecycle()
    val user by viewModel.currentUser.collectAsStateWithLifecycle()
    val profile = viewModel.profile
    val isMe = profile?.is_me == true || user?.ownerKey == userId
    val canFollow = !isMe && user != null && user?.role == UserRole.USER
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // numele: din profil (online) sau din postarile din cache
    val name = profile?.name?.takeIf { it.isNotBlank() }
        ?: posts.firstOrNull()?.let { "${it.user_first_name} ${it.user_last_name}".trim() }?.takeIf { it.isNotBlank() }
        ?: "Călător"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name = name, size = 72)
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                isMe -> "Acesta este profilul tău public"
                                viewModel.offline -> "Offline: se văd doar postările din cache"
                                viewModel.loading -> "Se încarcă…"
                                else -> "${profile?.followers ?: 0} urmăritori · urmărește ${profile?.following ?: 0}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (canFollow) {
                    Spacer(Modifier.height(12.dp))
                    val followed = userId in following
                    if (followed) {
                        OutlinedButton(onClick = viewModel::toggleFollow, enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Urmărești")
                        }
                    } else {
                        Button(onClick = viewModel::toggleFollow, enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Urmărește")
                        }
                    }
                }
            }
            item {
                val p = profile
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ProfileStat("${p?.post_count ?: posts.size}", "postări", Modifier.weight(1f))
                    ProfileStat("${p?.place_count ?: posts.map { it.place.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().size}", "locuri", Modifier.weight(1f))
                    ProfileStat(p?.average_rating?.takeIf { it > 0 }?.let { "%.1f★".format(it) } ?: "–", "rating", Modifier.weight(1f))
                    ProfileStat("${p?.likes_received ?: posts.sumOf { it.like_count }}", "aprecieri", Modifier.weight(1f))
                }
            }
            item {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Postări (${posts.size})") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Călătorii (${viewModel.trips.size})") })
                }
            }
            if (tab == 0) {
                if (posts.isEmpty()) item { EmptyHint("Nicio postare publică încă.") }
                items(posts, key = { it.id }) { post -> ProfilePostRow(post) { onOpenPost(post.id) } }
            } else {
                if (viewModel.trips.isEmpty()) {
                    item { EmptyHint(if (viewModel.offline) "Călătoriile se văd doar online." else "Nicio călătorie încă.") }
                }
                items(viewModel.trips, key = { it.id }) { trip ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenTrip(trip.id, trip.user_id.ifBlank { userId }) },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (trip.cover.isNotBlank()) PostPhoto(photo = trip.cover, height = 140.dp, rounded = false)
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(trip.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val period = when {
                                trip.start_date == 0L -> ""
                                trip.end_date == 0L || trip.end_date == trip.start_date -> formatVisitDate(trip.start_date)
                                else -> "${formatVisitDate(trip.start_date)} – ${formatVisitDate(trip.end_date)}"
                            }
                            if (period.isNotBlank()) Text(period, style = MaterialTheme.typography.bodySmall)
                            Text("${trip.post_count} postări", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileStat(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
}

@Composable
private fun ProfilePostRow(post: Post, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp)) {
        if (post.displayPhoto.isNotBlank()) PostPhoto(photo = post.displayPhoto, height = 180.dp, rounded = false)
        Column(modifier = Modifier.padding(12.dp)) {
            Text(post.title.ifBlank { post.place.ifBlank { "Postare" } }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (post.place.isNotBlank()) Text(post.place, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (post.rating > 0) RatingStars(post.rating, size = 14.dp)
                Spacer(Modifier.width(8.dp))
                Text("♥ ${post.like_count} · 💬 ${post.comment_count}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
