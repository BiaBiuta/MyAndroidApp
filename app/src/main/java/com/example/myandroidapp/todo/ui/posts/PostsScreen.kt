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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
    onLogout: () -> Unit
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
                    Button(onClick = onLogout) { Text("Logout") }
                }
            )
        },
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
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 32.dp),
            modifier = Modifier.padding(paddingValues)
        ) {
            item { Text("Todo", style = MaterialTheme.typography.h6) }
        }

        Column(modifier = Modifier.fillMaxSize()) {
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
                modifier = Modifier.padding(paddingValues)
            )
        }
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
