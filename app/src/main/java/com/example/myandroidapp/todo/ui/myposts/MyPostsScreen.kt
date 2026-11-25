package com.example.myandroidapp.todo.ui.myposts

import com.example.myandroidapp.core.data.remote.toUserMessage
import com.example.myandroidapp.core.data.remote.OfflineException
import com.example.myandroidapp.core.logging.SafeLog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
import com.example.myandroidapp.auth.data.local.isAdmin
import com.example.myandroidapp.todo.data.Post
import com.example.myandroidapp.todo.data.PostStatus
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.myandroidapp.todo.data.PostRepository
import com.example.myandroidapp.todo.ui.posts.PostList
import com.example.myapplication.todo.ui.items.PostRoleActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Functionalitate doar pentru rolul USER: postarile publice proprii, cu stergere. */
class MyPostsViewModel(
    private val postRepository: PostRepository,
    private val userRepository: RoleRepository
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val myPosts: Flow<List<Post>> = userRepository.currentUser.flatMapLatest { user ->
        if (user == null || user.isAdmin) flowOf(emptyList())
        else postRepository.publicItemsOf(user.ownerKey)
    }

    var message by mutableStateOf<String?>(null)
        private set

    fun consumeMessage() {
        message = null
    }

    fun delete(post: Post) {
        viewModelScope.launch {
            val user = userRepository.currentUserOnce()
            if (user == null || post.user_id != user.ownerKey) {
                message = "Puteți șterge doar postările proprii."
                return@launch
            }
            try {
                // si offline: postarea dispare imediat, se sterge pe server la sincronizare
                val result = postRepository.deletePost(post.id, user.email)
                message = com.example.myandroidapp.todo.ui.post.writeResultMessage(result, "Postarea a fost ștearsă.")
            } catch (e: Exception) {
                SafeLog.w("MyPostsViewModel", "delete failed", e)
                message = e.toUserMessage("Postarea nu a putut fi ștearsă.")
            }
        }
    }

    /** Publica o ciorna proprie (si offline: se trimite la sincronizare). */
    fun publish(post: Post) {
        viewModelScope.launch {
            val user = userRepository.currentUserOnce()
            if (user == null || post.user_id != user.ownerKey) {
                message = "Puteți publica doar postările proprii."
                return@launch
            }
            message = try {
                com.example.myandroidapp.todo.ui.post.writeResultMessage(
                    postRepository.publishDraft(post),
                    "Ciorna a fost publicată."
                )
            } catch (e: Exception) {
                SafeLog.w("MyPostsViewModel", "publish failed", e)
                e.toUserMessage("Ciorna nu a putut fi publicată.")
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
                MyPostsViewModel(app.container.postRepository, app.container.roleRepository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPostsScreen(onBack: () -> Unit, onEditPost: (id: String) -> Unit = {}, onOpenPost: (id: String) -> Unit = {}) {
    val viewModel = viewModel<MyPostsViewModel>(factory = MyPostsViewModel.Factory)
    val posts by viewModel.myPosts.collectAsStateWithLifecycle(initialValue = emptyList())
    val snackbarHostState = remember { SnackbarHostState() }
    var postToDelete by remember { mutableStateOf<Post?>(null) }
    // 0 = toate, 1 = publicate, 2 = ciorne
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val drafts = posts.filter { it.status == PostStatus.DRAFT }
    val published = posts.filter { it.status != PostStatus.DRAFT }
    val shown = when (tab) {
        1 -> published
        2 -> drafts
        else -> posts
    }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Postările mele") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Înapoi")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (posts.isEmpty()) {
                Text(
                    "Nu ai încă postări. Adaugă una din ecranul principal (butonul +).",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Toate (${posts.size})") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Publicate (${published.size})") })
                        Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Ciorne (${drafts.size})") })
                    }
                    if (tab == 2 && drafts.isNotEmpty()) {
                        Text(
                            "Ciornele le vezi doar tu. Apasă „Publică” când sunt gata.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                    PostList(
                        posts = shown,
                        emptyText = if (tab == 2) "Nu ai ciorne." else "Nu ai postări publicate.",
                        onPostClick = onOpenPost,
                        modifier = Modifier.fillMaxSize(),
                        // toate sunt ale utilizatorului: se vede starea sincronizarii fiecareia
                        isOwn = { true },
                        actions = { post ->
                            PostRoleActions(
                                canDelete = true,
                                canReport = false,
                                alreadyReported = false,
                                onDelete = { postToDelete = post },
                                onReport = {},
                                canEdit = true,
                                onEdit = { onEditPost(post.id) },
                                canPublish = post.status == PostStatus.DRAFT,
                                onPublish = { viewModel.publish(post) }
                            )
                        }
                    )
                }
            }
        }
    }

    postToDelete?.let { post ->
        AlertDialog(
            onDismissRequest = { postToDelete = null },
            title = { Text("Ștergeți postarea?") },
            text = { Text("Postarea va fi ștearsă definitiv.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.delete(post)
                    postToDelete = null
                }) { Text("Șterge") }
            },
            dismissButton = { TextButton(onClick = { postToDelete = null }) { Text("Anulează") } }
        )
    }
}
