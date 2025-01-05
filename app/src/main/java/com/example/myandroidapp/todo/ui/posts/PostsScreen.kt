package com.example.myapplication.todo.ui.items


import MyLocationViewModel
import android.app.Application
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myandroidapp.R
import com.example.myandroidapp.maps.MyMap
import com.example.myandroidapp.todo.data.Location
import com.example.myandroidapp.todo.ui.posts.PostList
import com.example.myandroidapp.todo.ui.posts.PostsViewModel
import com.example.myandroidapp.ui.theme.Purple80
import com.example.myapplication.network.MyNetworkStatusViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostsScreen(onItemClick: (id: String?) -> Unit, onAddItem: () -> Unit, onLogout: () -> Unit) {
    Log.d("ItemsScreen", "recompose")
    val itemsViewModel = viewModel<PostsViewModel>(factory = PostsViewModel.Factory)

    val itemsUiState by itemsViewModel.uiState.collectAsStateWithLifecycle(
        initialValue = listOf()
    )

    val myNetworkStatusViewModel = viewModel<MyNetworkStatusViewModel>(
        factory = MyNetworkStatusViewModel.Factory(
            LocalContext.current.applicationContext as Application
        )
    )
    var showMapDialog by rememberSaveable { mutableStateOf(false) }
    var selectedLocation by rememberSaveable { mutableStateOf<Pair<Double, Double>?>(null) }

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
            FloatingActionButton(
                onClick = {
                    Log.d("ItemsScreen", "add")
                    onAddItem()
                },
            ) { Icon(Icons.Rounded.Add, "Add") }
        }
    ) {

        Column(
            modifier = Modifier.fillMaxSize()
        ) {

            Row() {


                        PostList(
                            posts = itemsUiState ,
                            onPostClick = { postId ->
                                // Find post by ID and handle location
                                val post = itemsUiState.find { it.id == postId }
                                post?.location?.let { location ->
                                    handlePostClick(location) { location ->
                                        selectedLocation = location
                                        showMapDialog = true
                                    }
                                }
                            },
                            modifier = Modifier.padding(it)
                        )


            }
        }
    }
    if (showMapDialog) {
        AlertDialog(
            onDismissRequest = { showMapDialog = false },
            title = { Text("Post Location") },
            text = {
                selectedLocation?.let { location ->
                    val x = Location(location.first, location.second)
                    ShowMyLocationForPost(x, modifier = Modifier.fillMaxSize())
                } ?: Text("Location not available")
            },
            confirmButton = {
                Button(onClick = { showMapDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
fun handlePostClick(location: Location?, onShowMap: (Pair<Double,Double>) -> Unit) {
    if (location != null) {
        val transformedLocation = transformLocation(location)
        transformedLocation?.let {
            onShowMap(Pair(it.latitude, it.longitude))
        }
    } else {
        Log.w("PostClick", "Location is null.")
    }
}
fun transformLocation(location: Location): Location? {
    return location
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
@Preview
@Composable
fun PreviewItemsScreen() {
    PostsScreen(onItemClick = {}, onAddItem = {}, onLogout = {})
}
