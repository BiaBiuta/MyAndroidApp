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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker

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

/** Harta osmdroid cu un marcaj pentru fiecare postare; se incadreaza pe toate marcajele. */
@Composable
private fun PostsMap(posts: List<Post>, onSelect: (Post) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val currentOnSelect by rememberUpdatedState(onSelect)
    val mapView = remember {
        OsmConfig.init(context)
        MapView(context).apply {
            setTileSource(OsmConfig.tileSource)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(6.5)
            controller.setCenter(GeoPoint(DEFAULT_LATITUDE, DEFAULT_LONGITUDE))
            overlays.add(CopyrightOverlay(context))
        }
    }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { problem = OsmConfig.tileProblem() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // marcajele se refac cand se schimba lista (filtru, postari noi prin WebSocket)
    LaunchedEffect(posts) {
        mapView.overlays.removeAll { it is Marker }
        posts.forEach { post ->
            mapView.overlays.add(Marker(mapView).apply {
                position = GeoPoint(post.location.latitude, post.location.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = post.title.ifBlank { post.place }
                setOnMarkerClickListener { _, _ ->
                    currentOnSelect(post)
                    true
                }
            })
        }
        when {
            posts.size == 1 -> {
                mapView.controller.setZoom(12.0)
                mapView.controller.setCenter(GeoPoint(posts[0].location.latitude, posts[0].location.longitude))
            }
            posts.size > 1 -> {
                val box = BoundingBox.fromGeoPoints(posts.map { GeoPoint(it.location.latitude, it.location.longitude) })
                // dupa ce harta are dimensiuni (altfel incadrarea nu se poate calcula)
                mapView.post { mapView.zoomToBoundingBox(box.increaseByScale(1.3f), false) }
            }
        }
        mapView.invalidate()
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        problem?.let { MapProblem(it, Modifier.align(Alignment.BottomCenter)) }
    }
}
