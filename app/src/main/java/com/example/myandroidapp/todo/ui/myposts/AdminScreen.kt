package com.example.myandroidapp.todo.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myandroidapp.auth.data.local.UserEntity
import com.example.myandroidapp.auth.data.local.isAdmin
import com.example.myandroidapp.todo.data.local.ReportEntity
import com.example.myandroidapp.todo.data.local.ReportedPost
import com.example.myandroidapp.todo.ui.posts.PostPhoto
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Place
import androidx.compose.foundation.layout.size
import java.text.DateFormat
import java.util.Date

/** Panoul de administrare (doar ADMIN): moderare + gestionare utilizatori. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val viewModel = viewModel<AdminViewModel>(factory = AdminViewModel.Factory)
    val reported by viewModel.reportedPosts.collectAsStateWithLifecycle(initialValue = emptyList())
    val users by viewModel.users.collectAsStateWithLifecycle(initialValue = emptyList())
    val reportsByPost by viewModel.pendingReportsByPost.collectAsStateWithLifecycle(initialValue = emptyMap())
    val me by viewModel.currentUser.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Dialogul de raspuns: postarea + decizia (true = sterge postarea, false = respinge)
    var answering by remember { mutableStateOf<Pair<ReportedPost, Boolean>?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Administrare") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Înapoi")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Protectie: daca rolul a fost schimbat intre timp, nu mai aratam nimic
            if (me != null && me?.isAdmin != true) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nu mai aveți drepturi de administrator.")
                }
                return@Column
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = {
                    BadgedBox(badge = { if (reported.isNotEmpty()) Badge { Text("${reported.size}") } }) {
                        Text("Raportări")
                    }
                })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Utilizatori (${users.size})") })
            }
            if (viewModel.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            when (tab) {
                0 -> ReportedPostsList(
                    reported = reported,
                    reportsByPost = reportsByPost,
                    users = users,
                    enabled = !viewModel.busy,
                    onDelete = { answering = it to true },
                    onDismiss = { answering = it to false }
                )
                else -> UsersList(
                    users = users,
                    currentUserId = me?.email,
                    enabled = !viewModel.busy,
                    onToggleRole = viewModel::toggleRole,
                    onToggleBlocked = viewModel::toggleBlocked
                )
            }
        }
    }

    answering?.let { (item, delete) ->
        AnswerReportDialog(
            reported = item,
            deletePost = delete,
            onDismiss = { answering = null },
            onConfirm = { reply, authorMessage ->
                if (delete) viewModel.deleteReportedPost(item, reply, authorMessage)
                else viewModel.dismissReports(item, reply)
                answering = null
            }
        )
    }
}

/**
 * Adminul scrie raspunsul pentru cei care au raportat si, la stergere,
 * mesajul pentru autorul postarii.
 */
@Composable
private fun AnswerReportDialog(
    reported: ReportedPost,
    deletePost: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (reply: String, authorMessage: String) -> Unit
) {
    var reply by rememberSaveable {
        mutableStateOf(
            if (deletePost) AdminViewModel.DEFAULT_RESOLVED_REPLY else AdminViewModel.DEFAULT_REJECTED_REPLY
        )
    }
    var authorMessage by rememberSaveable { mutableStateOf(AdminViewModel.DEFAULT_AUTHOR_MESSAGE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deletePost) "Șterge postarea" else "Respinge raportările") },
        text = {
            Column {
                Text(
                    if (deletePost) "Postarea va fi eliminată, iar raportările marcate ca rezolvate."
                    else "Postarea rămâne, iar raportările sunt marcate ca respinse.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = reply,
                    onValueChange = { if (it.length <= 300) reply = it },
                    label = { Text("Mesaj pentru cei care au raportat") },
                    supportingText = {
                        Text("${reported.reportCount} utilizatori îl vor vedea în „Raportările mele”.")
                    },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                if (deletePost) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = authorMessage,
                        onValueChange = { if (it.length <= 300) authorMessage = it },
                        label = { Text("Mesaj pentru autorul postării") },
                        supportingText = { Text("Autorul îl va vedea la următoarea intrare în aplicație.") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reply, authorMessage) },
                enabled = reply.isNotBlank() && (!deletePost || authorMessage.isNotBlank())
            ) {
                Text(if (deletePost) "Șterge și trimite" else "Respinge și trimite")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } }
    )
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

/**
 * Postarile raportate, afisate complet: autorul, data, textul intreg, poza, locatia,
 * apoi fiecare raportare (cine a raportat, motivul, cand) si actiunile adminului.
 */
@Composable
private fun ReportedPostsList(
    reported: List<ReportedPost>,
    reportsByPost: Map<String, List<ReportEntity>>,
    users: List<UserEntity>,
    enabled: Boolean,
    onDelete: (ReportedPost) -> Unit,
    onDismiss: (ReportedPost) -> Unit
) {
    if (reported.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Nicio postare raportată.\nRaportările utilizatorilor apar aici în timp real.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        items(reported, key = { it.post.id }) { item ->
            val post = item.post
            val reports = reportsByPost[post.id].orEmpty()
            // autorul: e-mailul contului care are acest ownerKey, altfel numele de pe postare
            val authorEmail = users.firstOrNull { it.ownerKey == post.user_id }?.email
            val authorName = "${post.user_first_name} ${post.user_last_name}".trim()
            val author = authorEmail ?: authorName.ifBlank { post.user_id.ifBlank { "necunoscut" } }

            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Antet: autorul postarii + numarul de raportari
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Postarea lui $author",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (post.created_at.isNotBlank()) {
                                Text(
                                    "Publicată: ${post.created_at.take(16).replace('T', ' ')}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Badge(containerColor = MaterialTheme.colorScheme.error) {
                            Text("${item.reportCount} raportări")
                        }
                    }

                    // Continutul complet al postarii
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = post.description.ifBlank { "(fără descriere)" },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (post.photo.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        PostPhoto(photo = post.photo)
                    }
                    if (post.location.latitude != 0.0 || post.location.longitude != 0.0) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = "Locație",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "%.4f, %.4f".format(post.location.latitude, post.location.longitude),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Raportarile: cine, de ce, cand
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Raportări", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (reports.isEmpty()) {
                        Text(
                            "Motive: ${item.reasons.orEmpty()}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    reports.forEach { report ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Flag,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp).padding(top = 2.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("„${report.reason}”", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "de ${report.reporterId} · ${formatTime(report.createdAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onDismiss(item) }, enabled = enabled) { Text("Respinge") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onDelete(item) }, enabled = enabled) { Text("Șterge postarea") }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsersList(
    users: List<UserEntity>,
    currentUserId: String?,
    enabled: Boolean,
    onToggleRole: (UserEntity) -> Unit,
    onToggleBlocked: (UserEntity) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        items(users, key = { it.email }) { user ->
            val isMe = user.email == currentUserId
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                user.email + if (isMe) " (tu)" else "",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                (if (user.isBlocked) "BLOCAT · " else "") + "postări marcate cu: ${user.ownerKey}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (user.isBlocked) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AssistChip(
                            onClick = {},
                            label = { Text(if (user.isAdmin) "Admin" else "Utilizator") }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onToggleRole(user) }, enabled = enabled) {
                            Text(if (user.isAdmin) "Retrogradează" else "Fă administrator")
                        }
                        if (!isMe) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { onToggleBlocked(user) }, enabled = enabled) {
                                Text(if (user.isBlocked) "Deblochează" else "Blochează")
                            }
                        }
                    }
                }
            }
        }
    }
}
