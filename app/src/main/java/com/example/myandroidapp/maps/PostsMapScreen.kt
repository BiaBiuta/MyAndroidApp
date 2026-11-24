package com.example.myandroidapp.maps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.example.myandroidapp.todo.ui.post.formatVisitDate
import com.example.myandroidapp.todo.ui.post.isSet
import com.example.myandroidapp.todo.ui.posts.PostPhoto
import com.example.myandroidapp.todo.ui.posts.RatingStars
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

/** Postarile cu loc, pentru harta: toate cele vizibile sau doar ale utilizatorului. */
data class MapPosts(val all: List<Post> = emptyList(), val mine: List<Post> = emptyList())

class PostsMapViewModel(postRepository: PostRepository, roleRepository: RoleRepository) : ViewModel() {
    val posts: StateFlow<MapPosts> = combine(postRepository.itemStream, roleRepository.currentUser) { posts, user ->
        val owner = user?.ownerKey
        // pe harta: postarile publicate (ciornele doar ale mele), cu coordonate
        val located = posts.filter { it.location.isSet && (it.status == PostStatus.ACTIVE || it.user_id == owner) }
        MapPosts(all = located, mine = located.filter { owner != null && it.user_id == owner })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapPosts())

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication
                PostsMapViewModel(app.container.postRepository, app.container.roleRepository)
            }
        }
    }
}

/**
 * Harta postarilor (OpenStreetMap): fiecare loc vizitat e un marcaj; atingerea lui arata
 * postarea, de unde o poti deschide. "Ale mele" = jurnalul tau de calatorie pe harta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostsMapScreen(onBack: () -> Unit, onOpenPost: (id: String) -> Unit) {
    val viewModel = viewModel<PostsMapViewModel>(factory = PostsMapViewModel.Factory)
    val data by viewModel.posts.collectAsStateWithLifecycle()
    var mineOnly by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = if (mineOnly) data.mine else data.all
    val selected = shown.find { it.id == selectedId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Harta postărilor") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            PostsMap(posts = shown, onSelect = { selectedId = it.id }, modifier = Modifier.fillMaxSize())

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
            ) {
                FilterChip(
                    selected = !mineOnly,
                    onClick = { mineOnly = false; selectedId = null },
                    label = { Text("Toate (${data.all.size})") },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                FilterChip(
                    selected = mineOnly,
                    onClick = { mineOnly = true; selectedId = null },
                    label = { Text("Ale mele (${data.mine.size})") },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }

            if (shown.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 3.dp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                ) {
                    Text(
                        if (mineOnly) "Postările tale cu loc apar aici. Alege locul când creezi o postare."
                        else "Încă nu există postări cu loc pe hartă.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            selected?.let { post ->
                SelectedPostCard(
                    post = post,
                    onOpen = { onOpenPost(post.id) },
                    onClose = { selectedId = null },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun SelectedPostCard(post: Post, onOpen: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        if (post.displayPhoto.isNotBlank()) PostPhoto(photo = post.displayPhoto, height = 140.dp, rounded = false)
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    post.title.ifBlank { post.place.ifBlank { "Postare" } },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Închide") }
            }
            if (post.place.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(post.place, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                if (post.rating > 0) RatingStars(post.rating)
                if (post.visit_date > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(formatVisitDate(post.visit_date), style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Deschide postarea") }
        }
    }
}

/** Harta (MapLibre + OpenFreeMap) cu un marcaj pentru fiecare postare; se incadreaza pe toate. */
@Composable
private fun PostsMap(posts: List<Post>, onSelect: (Post) -> Unit, modifier: Modifier = Modifier) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // marcaj -> postare (pentru atingerea marcajului)
    val markerPosts = remember { mutableMapOf<Long, Post>() }
    val mapView = rememberMapLibre { ready ->
        ready.cameraPosition = CameraPosition.Builder()
            .target(LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE))
            .zoom(5.5)
            .build()
        ready.setOnMarkerClickListener { marker ->
            markerPosts[marker.id]?.let(currentOnSelect)
            true
        }
        map = ready
    }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { problem = OsmConfig.tileProblem() }

    // marcajele se refac cand se schimba lista (filtru, postari noi prin WebSocket)
    LaunchedEffect(map, posts) {
        val ready = map ?: return@LaunchedEffect
        ready.removeAnnotations()
        markerPosts.clear()
        posts.forEach { post ->
            val marker = ready.addMarker(
                MarkerOptions()
                    .position(LatLng(post.location.latitude, post.location.longitude))
                    .title(post.title.ifBlank { post.place })
            )
            markerPosts[marker.id] = post
        }
        val points = posts.map { LatLng(it.location.latitude, it.location.longitude) }
        when {
            points.size == 1 -> ready.animateCamera(CameraUpdateFactory.newLatLngZoom(points[0], 12.0))
            points.size > 1 -> {
                val bounds = LatLngBounds.Builder().includes(points).build()
                // dupa ce harta are dimensiuni (altfel incadrarea nu se poate calcula)
                mapView.post { ready.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120)) }
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        problem?.let { MapProblem(it, Modifier.align(Alignment.BottomCenter)) }
    }
}
