package com.example.myandroidapp.todo.ui.detail

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
    roleRepository: RoleRepository
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

    // ---------- comentarii (de pe server; offline: mesaj, fara lista) ----------
    var comments by mutableStateOf<List<com.example.myandroidapp.todo.data.remote.Comment>>(emptyList())
        private set
    var commentsError by mutableStateOf<String?>(null)
        private set
    var commentsLoading by mutableStateOf(false)
        private set
    var commentDraft by mutableStateOf("")
        private set
    var sending by mutableStateOf(false)
        private set

    init {
        // comentariile se reincarca la deschidere si cand se schimba numarul lor (WebSocket)
        viewModelScope.launch {
            state.map { it.post?.id to it.post?.comment_count }
                .distinctUntilChanged()
                .collect { (id, _) -> if (id != null) loadComments(id) }
        }
    }

    fun load(id: String) {
        postId.value = id
    }

    private suspend fun loadComments(id: String) {
        val post = state.value.post ?: return
        if (post.status != PostStatus.ACTIVE) {
            comments = emptyList()
            return
        }
        commentsLoading = true
        try {
            comments = postRepository.comments(id)
            commentsError = null
        } catch (e: Exception) {
            commentsError = if (e is com.example.myandroidapp.core.data.remote.OfflineException)
                "Comentariile se văd doar cu conexiune la server."
            else e.toUserMessage("Comentariile nu au putut fi încărcate.")
        }
        commentsLoading = false
    }

    fun onCommentChange(value: String) {
        if (value.length <= 500) commentDraft = value
    }

    fun sendComment() {
        val post = state.value.post ?: return
        val text = commentDraft.trim()
        if (text.isEmpty() || !state.value.canInteract) return
        viewModelScope.launch {
            sending = true
            try {
                postRepository.addComment(post.id, text)
                commentDraft = ""
                loadComments(post.id)
            } catch (e: Exception) {
                message = if (e is com.example.myandroidapp.core.data.remote.OfflineException)
                    "Comentariile au nevoie de conexiune la server."
                else e.toUserMessage("Comentariul nu a putut fi trimis.")
            }
            sending = false
        }
    }

    fun deleteComment(comment: com.example.myandroidapp.todo.data.remote.Comment) {
        val post = state.value.post ?: return
        viewModelScope.launch {
            try {
                postRepository.deleteComment(comment.id)
                loadComments(post.id)
            } catch (e: Exception) {
                message = e.toUserMessage("Comentariul nu a putut fi șters.")
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
                PostDetailViewModel(app.container.postRepository, app.container.roleRepository)
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
fun PostDetailScreen(postId: String, onBack: () -> Unit, onEdit: (id: String) -> Unit) {
    val viewModel = viewModel<PostDetailViewModel>(factory = PostDetailViewModel.Factory)
    LaunchedEffect(postId) { viewModel.load(postId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
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
            if (post.displayPhoto.isNotBlank()) {
                PostPhoto(photo = post.displayPhoto, height = 280.dp, rounded = false)
            }
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (post.title.isNotBlank()) {
                    Text(post.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                if (post.place.isNotBlank()) {
                    IconLine(Icons.Default.Place, post.place)
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
                Text(
                    "${authorName(post)} · ${formatPostDate(post.created_at)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

                if (post.status == PostStatus.ACTIVE) {
                    CommentsSection(viewModel, canComment = state.canInteract)
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

/** Comentariile postarii: lista, campul de scris si stergerea (autor / autorul postarii / admin). */
@Composable
private fun CommentsSection(viewModel: PostDetailViewModel, canComment: Boolean) {
    HorizontalDivider()
    Text(
        "Comentarii (${viewModel.comments.size})",
        style = MaterialTheme.typography.titleMedium
    )
    if (viewModel.commentsLoading && viewModel.comments.isEmpty()) {
        androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    viewModel.commentsError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (viewModel.comments.isEmpty() && viewModel.commentsError == null && !viewModel.commentsLoading) {
        Text(
            if (canComment) "Niciun comentariu încă. Scrie primul!" else "Niciun comentariu încă.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    viewModel.comments.forEach { comment ->
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${comment.author} · ${formatPostDate(comment.created_at)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(comment.text, style = MaterialTheme.typography.bodyMedium)
            }
            if (comment.can_delete) {
                IconButton(onClick = { viewModel.deleteComment(comment) }) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.Delete,
                        contentDescription = "Șterge comentariul",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    if (canComment) {
        androidx.compose.material3.OutlinedTextField(
            value = viewModel.commentDraft,
            onValueChange = viewModel::onCommentChange,
            label = { Text("Scrie un comentariu") },
            enabled = !viewModel.sending,
            supportingText = { Text("${viewModel.commentDraft.length}/500") },
            trailingIcon = {
                IconButton(
                    onClick = viewModel::sendComment,
                    enabled = !viewModel.sending && viewModel.commentDraft.isNotBlank()
                ) {
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.Send, contentDescription = "Trimite comentariul")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
