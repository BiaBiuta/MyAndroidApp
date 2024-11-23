package com.example.myapplication.todo.ui.items

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myandroidapp.R
import com.example.myandroidapp.core.Result
import com.example.myandroidapp.todo.data.Post
import com.example.myandroidapp.todo.ui.posts.PostList
import com.example.myandroidapp.todo.ui.posts.PostsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostsScreen(onItemClick: (id: String?) -> Unit, onAddItem: () -> Unit, onLogout: () -> Unit) {
    Log.d("ItemsScreen", "recompose")
    val itemsViewModel = viewModel<PostsViewModel>(factory = PostsViewModel.Factory)

    val itemsUiState by itemsViewModel.uiState.collectAsStateWithLifecycle(
        initialValue = Result.Success(listOf())
    )
//    val myNetworkStatusViewModel = viewModel<MyNetworkStatusViewModel>(
//        factory = MyNetworkStatusViewModel.Factory(
//            LocalContext.current.applicationContext as Application
//        )
//    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.posts)) },
                actions = {
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
//            Row(
//                modifier = Modifier.padding(10.dp)
//
//            ) {
//                androidx.compose.material.Text(
//                    "Is online: ${myNetworkStatusViewModel.uiState}",
//                    style = MaterialTheme.typography.h5,
//                )
//            }
            //Divider(color = Purple500, thickness = 2.dp)
            Row() {
                when (itemsUiState) {
                    is Result.Success ->
                        PostList(
                            posts = (itemsUiState as Result.Success<List<Post>>).data,
                            onPostClick = onItemClick,
                            modifier = Modifier.padding(it)
                        )

                    is Result.Loading -> CircularProgressIndicator(modifier = Modifier.padding(it))
                    is Result.Error -> Text(
                        text = "Failed to load items - ${(itemsUiState as Result.Error).exception?.message}",
                        modifier = Modifier.padding(it)
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun PreviewItemsScreen() {
    PostsScreen(onItemClick = {}, onAddItem = {}, onLogout = {})
}
