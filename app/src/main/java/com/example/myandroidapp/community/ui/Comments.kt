package com.example.myandroidapp.community.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.community.data.CommunityRepository
import com.example.myandroidapp.core.data.remote.OfflineException
import com.example.myandroidapp.core.data.remote.toUserMessage
import com.example.myandroidapp.todo.data.PostRepository
import com.example.myandroidapp.todo.data.remote.Comment
import kotlinx.coroutines.launch

/**
 * Comentariile unei postari, ca intr-o retea sociala reala:
 *  - fire de discutie: raspunsuri sub comentariul la care raspund;
 *  - aprecieri pe comentarii, editarea propriilor comentarii (marcate "editat");
 *  - stergere (autorul, autorul postarii sau adminul), cu confirmare;
 *  - timp relativ ("acum 5 min"), avatar cu initiala, numele duce la profil;
 *  - se actualizeaza singure cand altcineva comenteaza (eveniment WebSocket "comments").
 * Comentariile schimba ce vad toti, deci au nevoie de conexiune; offline apare un mesaj clar.
 */
class CommentsViewModel(
    private val postRepository: PostRepository,
    private val community: CommunityRepository
) : ViewModel() {
    var comments by mutableStateOf<List<Comment>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var draft by mutableStateOf("")
        private set
    var replyTo by mutableStateOf<Comment?>(null)
        private set
    var editing by mutableStateOf<Comment?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private var postId: String? = null

    init {
        // altcineva a comentat / editat / apreciat: reincarcam (doar postarea deschisa)
        viewModelScope.launch {
            postRepository.commentChanges.collect { id -> if (id == postId) reload() }
        }
    }

    fun load(id: String) {
        if (postId == id) return
        postId = id
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val id = postId ?: return
        loading = comments.isEmpty()
        try {
            comments = postRepository.comments(id)
            error = null
        } catch (e: Exception) {
            error = if (e is OfflineException) "Comentariile se văd doar cu conexiune la server."
            else e.toUserMessage("Comentariile nu au putut fi încărcate.")
        }
        loading = false
    }

    fun consumeMessage() {
        message = null
    }

    fun onDraftChange(value: String) {
        if (value.length <= 500) draft = value
    }

    fun startReply(comment: Comment) {
        editing = null
        replyTo = comment
    }

    fun startEdit(comment: Comment) {
        replyTo = null
        editing = comment
        draft = comment.text
    }

    fun cancel() {
        if (editing != null) draft = ""
        replyTo = null
        editing = null
    }

    fun send() {
        val id = postId ?: return
        val text = draft.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            sending = true
            try {
                val edit = editing
                if (edit != null) community.editComment(edit.id, text)
                else postRepository.addComment(id, text, replyTo?.id.orEmpty())
                draft = ""
                replyTo = null
                editing = null
                reload()
            } catch (e: Exception) {
                message = if (e is OfflineException) "Comentariile au nevoie de conexiune la server."
                else e.toUserMessage("Comentariul nu a putut fi trimis.")
            }
            sending = false
        }
    }

    fun toggleLike(comment: Comment) {
        // raspuns imediat in interfata, apoi confirmarea serverului
        val before = comments
        comments = comments.map {
            if (it.id != comment.id) it
            else it.copy(liked_by_me = !it.liked_by_me, like_count = it.like_count + if (it.liked_by_me) -1 else 1)
        }
        viewModelScope.launch {
            try {
                community.setCommentLiked(comment.id, !comment.liked_by_me)
            } catch (e: Exception) {
                comments = before
                message = if (e is OfflineException) "Aprecierile au nevoie de conexiune la server."
                else e.toUserMessage("Aprecierea nu a putut fi salvată.")
            }
        }
    }

    fun delete(comment: Comment) {
        viewModelScope.launch {
            try {
                postRepository.deleteComment(comment.id)
                if (editing?.id == comment.id || replyTo?.id == comment.id) cancel()
                reload()
            } catch (e: Exception) {
                message = e.toUserMessage("Comentariul nu a putut fi șters.")
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication
                CommentsViewModel(app.container.postRepository, app.container.communityRepository)
            }
        }
    }
}

/** Sectiunea de comentarii din detaliile postarii. onMessage = afisare in snackbar-ul ecranului. */
@Composable
fun CommentsSection(
    postId: String,
    canComment: Boolean,
    onOpenUser: (String) -> Unit,
    onMessage: (String) -> Unit
) {
    val viewModel = viewModel<CommentsViewModel>(key = "comments-$postId", factory = CommentsViewModel.Factory)
    LaunchedEffect(postId) { viewModel.load(postId) }
    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            onMessage(it)
            viewModel.consumeMessage()
        }
    }
    var toDelete by remember { mutableStateOf<Comment?>(null) }
    val focus = remember { FocusRequester() }
    val top = viewModel.comments.filter { it.parent_id.isEmpty() }
    val replies = viewModel.comments.filter { it.parent_id.isNotEmpty() }.groupBy { it.parent_id }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Text("Comentarii (${viewModel.comments.size})", style = MaterialTheme.typography.titleMedium)
        if (viewModel.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        viewModel.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (viewModel.comments.isEmpty() && viewModel.error == null && !viewModel.loading) {
            Text(
                if (canComment) "Niciun comentariu încă. Scrie primul!" else "Niciun comentariu încă.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        top.forEach { comment ->
            CommentRow(
                comment = comment,
                canReply = canComment,
                onOpenUser = onOpenUser,
                onLike = { viewModel.toggleLike(comment) },
                onReply = { viewModel.startReply(comment); runCatching { focus.requestFocus() } },
                onEdit = { viewModel.startEdit(comment); runCatching { focus.requestFocus() } },
                onDelete = { toDelete = comment }
            )
            replies[comment.id].orEmpty().forEach { reply ->
                Box(modifier = Modifier.padding(start = 44.dp)) {
                    CommentRow(
                        comment = reply,
                        canReply = canComment,
                        small = true,
                        onOpenUser = onOpenUser,
                        onLike = { viewModel.toggleLike(reply) },
                        onReply = { viewModel.startReply(reply); runCatching { focus.requestFocus() } },
                        onEdit = { viewModel.startEdit(reply); runCatching { focus.requestFocus() } },
                        onDelete = { toDelete = reply }
                    )
                }
            }
        }

        if (canComment) {
            val target = viewModel.replyTo
            val editing = viewModel.editing
            if (target != null || editing != null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            if (editing != null) "Editezi comentariul" else "Răspunzi lui ${target?.author}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = viewModel::cancel) { Icon(Icons.Default.Close, contentDescription = "Anulează") }
                    }
                }
            }
            OutlinedTextField(
                value = viewModel.draft,
                onValueChange = viewModel::onDraftChange,
                placeholder = { Text(if (target != null) "Scrie un răspuns…" else "Scrie un comentariu…") },
                enabled = !viewModel.sending,
                supportingText = { Text("${viewModel.draft.length}/500") },
                shape = RoundedCornerShape(24.dp),
                trailingIcon = {
                    IconButton(onClick = viewModel::send, enabled = !viewModel.sending && viewModel.draft.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (editing != null) "Salvează modificarea" else "Trimite")
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        }
    }

    toDelete?.let { comment ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Ștergi comentariul?") },
            text = {
                Text(
                    if (comment.parent_id.isEmpty()) "Se șterg și răspunsurile la acest comentariu."
                    else "Comentariul va dispărea pentru toți."
                )
            },
            confirmButton = { Button(onClick = { viewModel.delete(comment); toDelete = null }) { Text("Șterge") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Anulează") } }
        )
    }
}

@Composable
private fun CommentRow(
    comment: Comment,
    canReply: Boolean,
    small: Boolean = false,
    onOpenUser: (String) -> Unit,
    onLike: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Avatar(name = comment.author, size = if (small) 28 else 36, onClick = { onOpenUser(comment.user_id) })
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        comment.author,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onOpenUser(comment.user_id) }
                    )
                    Text(comment.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    relativeTime(comment.created_at) + if (comment.edited_at.isNotEmpty()) " · editat" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (canReply) SmallAction("Răspunde", onReply)
                if (comment.can_edit) SmallAction("Editează", onEdit)
                if (comment.can_delete) SmallAction("Șterge", onDelete)
            }
        }
        // aprecierea comentariului, cu numarul
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onLike, enabled = canReply, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (comment.liked_by_me) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (comment.liked_by_me) "Retrage aprecierea comentariului" else "Apreciază comentariul",
                    tint = if (comment.liked_by_me) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (comment.like_count > 0) {
                Text(
                    "${comment.like_count}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.semantics { contentDescription = "${comment.like_count} aprecieri" }
                )
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

/** Avatar cu initiala, intr-o culoare stabila pentru fiecare nume. */
@Composable
fun Avatar(name: String, size: Int = 40, onClick: (() -> Unit)? = null) {
    val palette = listOf(0xFF5C6BC0, 0xFF26A69A, 0xFFEF6C00, 0xFF8D6E63, 0xFFAB47BC, 0xFF42A5F5, 0xFF66BB6A, 0xFFEC407A)
    val color = Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .background(color, CircleShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    ) {
        Text(
            (name.firstOrNull() ?: '?').uppercase(),
            color = Color.White,
            style = if (size >= 48) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

/** "acum", "acum 5 min", "acum 3 h", "ieri", "acum 4 zile", apoi data. */
fun relativeTime(iso: String): String {
    val millis = try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse(iso.take(19))?.time ?: return ""
    } catch (e: Exception) {
        return ""
    }
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> "acum"
        minutes < 60 -> "acum $minutes min"
        minutes < 24 * 60 -> "acum ${minutes / 60} h"
        minutes < 48 * 60 -> "ieri"
        minutes < 7 * 24 * 60 -> "acum ${minutes / (24 * 60)} zile"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(millis))
    }
}
