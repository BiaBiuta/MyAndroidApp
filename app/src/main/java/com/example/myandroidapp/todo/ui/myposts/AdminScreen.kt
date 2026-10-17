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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.myandroidapp.todo.data.local.ReportedPost

/** Panoul de administrare (doar ADMIN): moderare + gestionare utilizatori. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val viewModel = viewModel<AdminViewModel>(factory = AdminViewModel.Factory)
    val reported by viewModel.reportedPosts.collectAsStateWithLifecycle(initialValue = emptyList())
    val users by viewModel.users.collectAsStateWithLifecycle(initialValue = emptyList())
    val me by viewModel.currentUser.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
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
                    enabled = !viewModel.busy,
                    onDelete = viewModel::deleteReportedPost,
                    onDismiss = viewModel::dismissReports
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
}

@Composable
private fun ReportedPostsList(
    reported: List<ReportedPost>,
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
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.post.description.ifBlank { "(fără descriere)" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Badge { Text("${item.reportCount}") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Motive: ${item.reasons.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Autor: ${item.post.user_first_name.ifBlank { item.post.user_id.ifBlank { "necunoscut" } }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onDismiss(item) }, enabled = enabled) { Text("Respinge") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { onDelete(item) }, enabled = enabled) { Text("Șterge postarea") }
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
