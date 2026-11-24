package com.example.myandroidapp.todo.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import com.example.myandroidapp.auth.data.local.isRegularUser
import com.example.myandroidapp.core.data.remote.toUserMessage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.example.myandroidapp.todo.data.Post
import com.example.myandroidapp.todo.data.PostRepository
import com.example.myandroidapp.todo.data.PostStatus
import com.example.myandroidapp.maps.OsmMap
import com.example.myandroidapp.todo.ui.post.formatVisitDate
import com.example.myandroidapp.todo.ui.post.isSet
import com.example.myandroidapp.todo.ui.post.writeResultMessage
import com.example.myandroidapp.todo.ui.posts.PostBadges
import com.example.myandroidapp.todo.ui.posts.PostPhoto
import com.example.myandroidapp.todo.ui.posts.RatingStars
import com.example.myandroidapp.todo.ui.posts.authorName
import com.example.myandroidapp.todo.ui.posts.formatPostDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Postarea deschisa + daca utilizatorul curent e autorul (poate edita / publica). */
data class PostDetailState(
    val post: Post? = null,
    val isMine: Boolean = false,
    val loaded: Boolean = false,
    // poate aprecia / comenta: cont USER propriu (nu admin, nu vizitator anonim)
    val canInteract: Boolean = false
)

class PostDetailViewModel(
    private val postRepository: PostRepository,
    roleRepository: RoleRepository,
    private val community: com.example.myandroidapp.community.data.CommunityRepository
) : ViewModel() {
    private val postId = MutableStateFlow<String?>(null)

    // urmareste postarea in Room: se actualizeaza singura (sincronizare, WebSocket, publicare)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<PostDetailState> =
        combine(postRepository.itemStream, roleRepository.currentUser, postId) { posts, user, id ->
            val post = posts.find { it.id == id }
            PostDetailState(
                post = post,
                isMine = post != null && user != null && !user.isBlocked && post.user_id == user.ownerKey,
                loaded = true,
                canInteract = user != null && user.isRegularUser && !user.isBlocked
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, PostDetailState())

    var message by mutableStateOf<String?>(null)
        private set

    fun load(id: String) {
        postId.value = id
    }

    /** Salvata ca idee de calatorie (doar pe telefon, merge si offline). */
    val saved: StateFlow<Boolean> = combine(community.saved, postId) { ids, id -> id != null && id in ids }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleSave() {
        val id = postId.value ?: return
        viewModelScope.launch { community.setSaved(id, !saved.value) }
    }

    /** Numele calatoriei postarii: din calatoriile proprii sau (pentru altii) de pe server. */
    var tripTitle by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            combine(state, community.myTrips) { st, trips -> st.post to trips }
                .collect { (post, trips) ->
                    val tripId = post?.trip_id.orEmpty()
                    tripTitle = when {
                        tripId.isEmpty() -> null
                        else -> trips.find { it.id == tripId }?.title
                            ?: runCatching { community.tripsOf(post!!.user_id).find { it.id == tripId }?.title }.getOrNull()
                    }
                }
        }
    }

    fun toggleLike() {
        val post = state.value.post ?: return
        if (!state.value.canInteract) return
        viewModelScope.launch {
            try {
                postRepository.setLiked(post.id, !post.liked_by_me)
            } catch (e: Exception) {
                message = if (e is com.example.myandroidapp.core.data.remote.OfflineException)
                    "Aprecierile au nevoie de conexiune la server."
                else e.toUserMessage("Aprecierea nu a putut fi salvată.")
            }
        }
    }

    fun consumeMessage() {
        message = null
    }

    fun publish() {
        val current = state.value
        val post = current.post ?: return
        if (!current.isMine || post.status != PostStatus.DRAFT) return
        viewModelScope.launch {
            message = try {
                writeResultMessage(postRepository.publishDraft(post), "Ciorna a fost publicată.")
            } catch (e: Exception) {
                "Ciorna nu a putut fi publicată."
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication
                PostDetailViewModel(app.container.postRepository, app.container.roleRepository, app.container.communityRepository)
            }
        }
    }
}

/**
 * Detaliile unei postari: poza mare, titlu, loc, rating, data vizitei, impresiile complete
 * si harta locului (OpenStreetMap), cu "Deschide în hărți" (orice aplicatie de harti de pe telefon).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDetailScreen(
    postId: String,
    onBack: () -> Unit,
    onEdit: (id: String) -> Unit,
    onOpenUser: (userId: String) -> Unit = {},
    onOpenTrip: (tripId: String, ownerId: String) -> Unit = { _, _ -> }
) {
    val viewModel = viewModel<PostDetailViewModel>(factory = PostDetailViewModel.Factory)
    LaunchedEffect(postId) { viewModel.load(postId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.post?.title?.ifBlank { null } ?: "Postare") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi")
                    }
                },
                actions = {
                    state.post?.let { post ->
                        // distribuie prin aplicatiile telefonului (text + poza, daca exista)
                        IconButton(onClick = { scope.launch { sharePost(context, post) } }) {
                            Icon(Icons.Default.Share, contentDescription = "Distribuie")
                        }
                        if (state.canInteract && post.status == PostStatus.ACTIVE && !post.isPrivate) {
                            IconButton(onClick = viewModel::toggleSave) {
                                Icon(
                                    if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = if (saved) "Scoate din salvate" else "Salvează ca idee de călătorie"
                                )
                            }
                        }
                    }
                    if (state.isMine) {
                        IconButton(onClick = { state.post?.let { onEdit(it.id) } }) {
                            Icon(Icons.Default.Edit, contentDescription = "Editează postarea")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val post = state.post
        if (post == null) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.loaded) "Postarea nu mai există." else "Se încarcă…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // toate pozele: glisare intre ele, cu indicator
            PhotoPager(post.allPhotos)
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (post.title.isNotBlank()) {
                    Text(post.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                if (post.place.isNotBlank()) {
                    IconLine(Icons.Default.Place, post.place)
                }
                viewModel.tripTitle?.let { trip ->
                    androidx.compose.material3.AssistChip(
                        onClick = { onOpenTrip(post.trip_id, post.user_id) },
                        label = { Text("Călătoria: $trip") },
                        leadingIcon = { Icon(Icons.Default.Luggage, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
                if (post.visit_date > 0) {
                    IconLine(Icons.Default.CalendarMonth, "Vizitat pe ${formatVisitDate(post.visit_date)}")
                }
                if (post.rating > 0) RatingStars(post.rating, size = 22.dp)
                PostBadges(post, showSyncStatus = state.isMine)

                if (state.isMine && post.status == PostStatus.DRAFT) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                "Este o ciornă: o vezi doar tu.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            FilledTonalButton(onClick = viewModel::publish) {
                                Icon(Icons.Default.Publish, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Publică")
                            }
                        }
                    }
                }

                if (post.description.isNotBlank()) {
                    Text(post.description, style = MaterialTheme.typography.bodyLarge)
                }

                HorizontalDivider()
                // autorul: avatar + nume -> profilul lui
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onOpenUser(post.user_id) }
                ) {
                    com.example.myandroidapp.community.ui.Avatar(authorName(post), size = 36)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(authorName(post), style = MaterialTheme.typography.titleSmall)
                        Text(
                            formatPostDate(post.created_at),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (post.status == PostStatus.ACTIVE) {
                    com.example.myandroidapp.todo.ui.posts.SocialRow(
                        post = post,
                        onToggleLike = if (state.canInteract) viewModel::toggleLike else null
                    )
                }

                if (post.location.isSet) {
                    Spacer(Modifier.height(4.dp))
                    Text("Pe hartă", style = MaterialTheme.typography.titleMedium)
                    OsmMap(
                        latitude = post.location.latitude,
                        longitude = post.location.longitude,
                        markerTitle = post.place,
                        interactive = false,
                        onClick = { openInMaps(context, post) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    OutlinedButton(onClick = { openInMaps(context, post) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Deschide în aplicația de hărți")
                    }
                }

                if (post.status == PostStatus.ACTIVE && !post.isPrivate) {
                    com.example.myandroidapp.community.ui.CommentsSection(
                        postId = post.id,
                        canComment = state.canInteract,
                        onOpenUser = onOpenUser,
                        onMessage = { text -> scope.launch { snackbarHostState.showSnackbar(text) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun IconLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Deschide locul in orice aplicatie de harti (geo:); daca nu exista, in browser pe OpenStreetMap. */
private fun openInMaps(context: android.content.Context, post: Post) {
    val lat = post.location.latitude
    val lon = post.location.longitude
    val label = Uri.encode(post.place.ifBlank { post.title }.ifBlank { "Loc vizitat" })
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")))
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=15/$lat/$lon"))
            )
        } catch (ignored: ActivityNotFoundException) {
        }
    }
}

/** Pozele postarii: una sub alta prin glisare, cu "2 / 5" si puncte. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoPager(photos: List<String>) {
    if (photos.isEmpty()) return
    if (photos.size == 1) {
        PostPhoto(photo = photos[0], height = 300.dp, rounded = false)
        return
    }
    val pager = androidx.compose.foundation.pager.rememberPagerState(pageCount = { photos.size })
    Box {
        androidx.compose.foundation.pager.HorizontalPager(state = pager) { page ->
            PostPhoto(photo = photos[page], height = 300.dp, rounded = false)
        }
        androidx.compose.material3.Surface(
            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
            contentColor = androidx.compose.ui.graphics.Color.White,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
        ) {
            Text("${pager.currentPage + 1} / ${photos.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
        ) {
            repeat(photos.size) { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == pager.currentPage) 9.dp else 7.dp)
                        .background(
                            androidx.compose.ui.graphics.Color.White.copy(alpha = if (index == pager.currentPage) 1f else 0.5f),
                            androidx.compose.foundation.shape.CircleShape
                        )
                )
            }
        }
    }
}

/**
 * Distribuie postarea prin aplicatiile telefonului: titlu, loc, stele, inceputul textului si un
 * link spre locul pe harta; poza principala atasata, daca o avem pe telefon (fisier sau din cache).
 */
private suspend fun sharePost(context: android.content.Context, post: Post) {
    val text = buildString {
        append(post.title.ifBlank { post.place.ifBlank { "Un loc de pe WanderLog" } })
        if (post.place.isNotBlank() && post.place != post.title) append(" — ").append(post.place)
        if (post.rating > 0) append("\n").append("★".repeat(post.rating)).append("☆".repeat(5 - post.rating))
        if (post.description.isNotBlank()) append("\n\n").append(post.description.take(280))
        if (post.location.isSet) {
            val lat = post.location.latitude
            val lon = post.location.longitude
            append("\n\nPe hartă: https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=14/$lat/$lon")
        }
        append("\n\nDescoperit pe WanderLog")
    }
    val image = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { shareableImage(context, post) }
    val intent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, post.title)
        if (image != null) {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, image)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            type = "text/plain"
        }
    }
    context.startActivity(Intent.createChooser(intent, "Distribuie postarea"))
}

/** Poza principala ca fisier partajabil (FileProvider): locala, din cache-ul pozelor sau descarcata. */
private fun shareableImage(context: android.content.Context, post: Post): Uri? = try {
    val photo = post.displayPhoto
    val source: java.io.File? = when {
        photo.isBlank() || photo.startsWith("data:") -> null
        photo.startsWith("/photos/") -> {
            val target = java.io.File(context.cacheDir, "share").apply { mkdirs() }.resolve("wanderlog-${post.id}.jpg")
            if (!target.exists()) {
                val request = okhttp3.Request.Builder().url(com.example.myandroidapp.core.data.remote.Api.absoluteUrl(photo)).build()
                com.example.myandroidapp.core.data.remote.Api.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return null
                    target.outputStream().use { out -> response.body?.byteStream()?.copyTo(out) }
                }
            }
            target
        }
        else -> java.io.File(photo).takeIf { it.exists() }
    }
    source?.let { androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
} catch (e: Exception) {
    null
}
