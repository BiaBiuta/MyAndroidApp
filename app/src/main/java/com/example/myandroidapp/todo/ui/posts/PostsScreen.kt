package com.example.myapplication.todo.ui.items

import MyLocationViewModel
import android.app.Application
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Person
import com.example.myandroidapp.auth.data.local.UserEntity
import com.example.myandroidapp.auth.data.local.isAdmin
import com.example.myandroidapp.todo.data.Post
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myandroidapp.R
import com.example.myandroidapp.maps.MyMap
import com.example.myandroidapp.todo.data.Location
import com.example.myandroidapp.todo.ui.posts.PostList
import com.example.myandroidapp.todo.ui.posts.PostsViewModel
import com.example.myandroidapp.ui.theme.Purple80
import com.example.myapplication.network.MyNetworkStatusViewModel
import com.ilazar.myanimationsapp.MyFloatingActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostsScreen(
    onItemClick: (id: String?) -> Unit,
    onAddItem: () -> Unit,
    onLogout: () -> Unit,
    onOpenPrivate: () -> Unit = {},
    // doar USER: postarile proprii
    onOpenMyPosts: () -> Unit = {},
    // doar ADMIN: moderare + gestionare utilizatori
    onOpenAdmin: () -> Unit = {}
) {
    Log.d("PostsScreen", "recompose")

    val itemsViewModel = viewModel<PostsViewModel>(factory = PostsViewModel.Factory)
    val myNetworkStatusViewModel = viewModel<MyNetworkStatusViewModel>(
        factory = MyNetworkStatusViewModel.Factory(LocalContext.current.applicationContext as Application)
    )

    var isEditing by remember { mutableStateOf(false) }
    var showMapDialog by rememberSaveable { mutableStateOf(false) }
    var selectedLocation by rememberSaveable { mutableStateOf<Pair<Double, Double>?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val itemsUiState by itemsViewModel.uiState.collectAsStateWithLifecycle(initialValue = listOf())
    val currentUser by itemsViewModel.currentUser.collectAsStateWithLifecycle()
    val reportedByMe by itemsViewModel.reportedByMe.collectAsStateWithLifecycle()
    var postToReport by remember { mutableStateOf<Post?>(null) }
    var postToDelete by remember { mutableStateOf<Post?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Confirmari / erori ale actiunilor pe rol (stergere, raportare)
    LaunchedEffect(itemsViewModel.message) {
        itemsViewModel.message?.let {
            snackbarHostState.showSnackbar(it)
            itemsViewModel.consumeMessage()
        }
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            delay(3000L)
            isEditing = false
            onAddItem()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.posts)) },
                actions = {
                    Text(
                        text = if (myNetworkStatusViewModel.uiState) "Online" else "Offline",
                        style = MaterialTheme.typography.body1,
                        color = if (myNetworkStatusViewModel.uiState) Purple80 else MaterialTheme.colors.error
                    )
                    // Sectiunea privata (cere din nou amprenta sau e-mail + parola)
                    Button(onClick = onOpenPrivate) { Text("Privat") }
                    Spacer(modifier = Modifier.width(4.dp))
                    Button(onClick = onLogout) { Text("Logout") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            MyFloatingActionButton(
                isEditing = isEditing,
                onClick = {
                    coroutineScope.launch {
                        isEditing = true
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            // Banda cu rolul curent + functionalitatea unica a rolului
            RoleBar(
                user = currentUser,
                onOpenMyPosts = onOpenMyPosts,
                onOpenAdmin = onOpenAdmin
            )
            if (itemsViewModel.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            PostList(
                posts = itemsUiState,
                onPostClick = { postId ->
                    val post = itemsUiState.find { it.id == postId }
                    if (post != null) {
                        Log.d("PostsScreen", "Post clicked: ${post.location?.latitude}, ${post.location?.longitude}")
                    }
                    post?.location?.let { location ->
                        handlePostClick(location) { location ->
                            selectedLocation = location
                            showMapDialog = true
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                actions = { post ->
                    PostRoleActions(
                        canDelete = itemsViewModel.canDelete(post, currentUser),
                        canReport = itemsViewModel.canReport(post, currentUser),
                        alreadyReported = post.id in reportedByMe,
                        onDelete = { postToDelete = post },
                        onReport = { postToReport = post }
                    )
                }
            )
        }
    }

    postToDelete?.let { post ->
        AlertDialog(
            onDismissRequest = { postToDelete = null },
            title = { Text("Ștergeți postarea?") },
            text = {
                Text(
                    if (currentUser?.isAdmin == true && post.user_id != currentUser?.ownerKey)
                        "Postarea va fi eliminată pentru toți utilizatorii (moderare)."
                    else "Postarea va fi ștearsă definitiv."
                )
            },
            confirmButton = {
                Button(onClick = {
                    itemsViewModel.deletePost(post)
                    postToDelete = null
                }) { Text("Șterge") }
            },
            dismissButton = {
                TextButton(onClick = { postToDelete = null }) { Text("Anulează") }
            }
        )
    }

    postToReport?.let { post ->
        ReportPostDialog(
            onDismiss = { postToReport = null },
            onConfirm = { reason ->
                itemsViewModel.reportPost(post, reason)
                postToReport = null
            }
        )
    }

    if (showMapDialog) {
        AlertDialog(
            onDismissRequest = { showMapDialog = false },
            title = { Text("Post Location") },
            text = {
                selectedLocation?.let { location ->
                    ShowMyLocationForPost(Location(location.first, location.second), Modifier.fillMaxSize())
                } ?: Text("Location not available")
            },
            confirmButton = {
                Button(onClick = { showMapDialog = false }) { Text("Close") }
            }
        )
    }

    EditMessage(shown = isEditing)
}

fun handlePostClick(location: Location?, onShowMap: (Pair<Double, Double>) -> Unit) {
    location?.let {
        onShowMap(Pair(it.latitude, it.longitude))
    } ?: Log.w("PostClick", "Location is null.")
}
@Composable
fun ShowMyLocationForPost(location: Location, modifier: Modifier) {
    val myLocationViewModel = viewModel<MyLocationViewModel>(
        factory = MyLocationViewModel.Factory(
            LocalContext.current.applicationContext as Application
        )
    )
    MyMap(location.latitude, location.longitude, modifier) { lat, lon ->
        myLocationViewModel.updateSelectedLocation(lat, lon)
    }
}
@Composable
private fun EditMessage(shown: Boolean) {
    AnimatedVisibility(
        visible = shown,
        enter = slideInVertically(
            initialOffsetY = { fullHeight -> -fullHeight },
            animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing)
        ),
        exit = slideOutVertically(
            targetOffsetY = { fullHeight -> -fullHeight },
            animationSpec = tween(durationMillis = 250, easing = FastOutLinearInEasing)
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colors.secondary,
            elevation = 4.dp
        ) {
            Text("You enter in add screen", modifier = Modifier.padding(16.dp))
        }
    }
}

//@Composable
//fun ShowMyLocationForPost(location: Location, modifier: Modifier) {
//    MyMap(location.latitude, location.longitude, modifier)
//}

@Preview
@Composable
fun PreviewPostsScreen() {
    PostsScreen(onItemClick = {}, onAddItem = {}, onLogout = {})
}


/** Banda de sub bara de sus: rolul curent si butonul catre functionalitatea unica a rolului. */
@Composable
fun RoleBar(user: UserEntity?, onOpenMyPosts: () -> Unit, onOpenAdmin: () -> Unit) {
    Surface(
        color = if (user?.isAdmin == true) Color(0xFFFFF3E0) else Color(0xFFE8EAF6),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (user?.isAdmin == true) Icons.Default.AdminPanelSettings else Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    user == null -> "Vizitator (fără cont)"
                    user.isAdmin -> "Administrator · ${user.email}"
                    else -> "Utilizator · ${user.email}"
                },
                style = MaterialTheme.typography.body2,
                modifier = Modifier.weight(1f)
            )
            when {
                user == null -> {}
                user.isAdmin -> TextButton(onClick = onOpenAdmin) { Text("Administrare") }
                else -> TextButton(onClick = onOpenMyPosts) { Text("Postările mele") }
            }
        }
    }
}

/**
 * Butoanele de sub o postare, in functie de rol:
 *  - ADMIN: Sterge (orice postare)
 *  - USER: Sterge (doar postarile proprii) si Raporteaza (postarile altora)
 */
@Composable
fun PostRoleActions(
    canDelete: Boolean,
    canReport: Boolean,
    alreadyReported: Boolean,
    onDelete: () -> Unit,
    onReport: () -> Unit
) {
    if (!canDelete && !canReport) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        if (canReport) {
            TextButton(onClick = onReport, enabled = !alreadyReported) {
                Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (alreadyReported) "Raportat" else "Raportează")
            }
        }
        if (canDelete) {
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Șterge")
            }
        }
    }
}

private val REPORT_REASONS = listOf(
    "Conținut nepotrivit",
    "Spam sau reclamă",
    "Informații false",
    "Încalcă drepturile cuiva"
)

@Composable
fun ReportPostDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var selected by remember { mutableStateOf(REPORT_REASONS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raportează postarea") },
        text = {
            Column {
                Text("De ce raportați această postare? Un administrator o va verifica.")
                Spacer(modifier = Modifier.height(8.dp))
                REPORT_REASONS.forEach { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == reason, onClick = { selected = reason })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == reason, onClick = { selected = reason })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(reason)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selected) }) { Text("Trimite") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    )
}
