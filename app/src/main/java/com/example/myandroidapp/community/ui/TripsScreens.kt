package com.example.myandroidapp.community.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.myandroidapp.community.data.CommunityRepository
import com.example.myandroidapp.community.data.RemoteTrip
import com.example.myandroidapp.community.data.TripEntity
import com.example.myandroidapp.core.data.remote.toUserMessage
import com.example.myandroidapp.maps.RouteMap
import com.example.myandroidapp.maps.RoutePoint
import com.example.myandroidapp.todo.data.Post
import com.example.myandroidapp.todo.data.PostRepository
import com.example.myandroidapp.todo.data.PostStatus
import com.example.myandroidapp.todo.ui.post.TripDialog
import com.example.myandroidapp.todo.ui.post.formatVisitDate
import com.example.myandroidapp.todo.ui.post.isSet
import com.example.myandroidapp.todo.ui.posts.PostPhoto
import com.example.myandroidapp.todo.ui.posts.RatingStars
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** O calatorie pentru afisare (proprie sau a altcuiva) + postarile ei vizibile. */
data class TripView(
    val id: String,
    val ownerId: String,
    val title: String,
    val description: String,
    val startDate: Long,
    val endDate: Long,
    val pending: Boolean,
    val posts: List<Post>
) {
    val cover: String get() = posts.firstOrNull { it.displayPhoto.isNotBlank() }?.displayPhoto.orEmpty()
    val places: Int get() = posts.map { it.place.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().size
    val averageRating: Double get() = posts.filter { it.rating > 0 }.map { it.rating }.average().takeIf { !it.isNaN() } ?: 0.0

    /** Perioada: datele calatoriei sau, daca lipsesc, intervalul vizitelor. */
    val period: String
        get() {
            val visits = posts.map { it.visit_date }.filter { it > 0 }
            val start = startDate.takeIf { it > 0 } ?: visits.minOrNull() ?: 0
            val end = endDate.takeIf { it > 0 } ?: visits.maxOrNull() ?: 0
            return when {
                start == 0L -> ""
                end == 0L || end == start -> formatVisitDate(start)
                else -> "${formatVisitDate(start)} – ${formatVisitDate(end)}"
            }
        }

    val days: Int
        get() {
            val visits = posts.map { it.visit_date }.filter { it > 0 }
            val start = startDate.takeIf { it > 0 } ?: visits.minOrNull() ?: return 0
            val end = endDate.takeIf { it > 0 } ?: visits.maxOrNull() ?: start
            return ((end - start) / 86_400_000L).toInt() + 1
        }
}

class TripsViewModel(
    postRepository: PostRepository,
    roleRepository: RoleRepository,
    private val community: CommunityRepository
) : ViewModel() {
    /** Calatoriile mele, cu postarile lor (din cache: merg si offline). */
    val myTrips: StateFlow<List<TripView>> = combine(community.myTrips, postRepository.itemStream) { trips, posts ->
        trips.map { it.toView(posts) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentUser = roleRepository.currentUser.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val allPosts = postRepository.itemStream.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var message by mutableStateOf<String?>(null)
        private set

    // ---------- calatoria deschisa (a mea sau a altcuiva) ----------
    private val remoteTrip = MutableStateFlow<RemoteTrip?>(null)
    private val openedId = MutableStateFlow<String?>(null)
    var loadingRemote by mutableStateOf(false)
        private set

    val opened: StateFlow<TripView?> = combine(openedId, community.myTrips, postRepository.itemStream, remoteTrip) { id, mine, posts, remote ->
        if (id == null) null
        else mine.find { it.id == id }?.toView(posts)
            ?: remote?.takeIf { it.id == id }?.let { trip ->
                TripView(trip.id, trip.user_id, trip.title, trip.description, trip.start_date, trip.end_date, false, postsOf(posts, trip.id))
            }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun open(tripId: String, ownerId: String) {
        openedId.value = tripId
        viewModelScope.launch {
            if (community.trip(tripId) != null) return@launch
            loadingRemote = true
            remoteTrip.value = try {
                community.tripsOf(ownerId).find { it.id == tripId }
            } catch (e: Exception) {
                message = e.toUserMessage("Călătoria nu a putut fi încărcată.")
                null
            }
            loadingRemote = false
        }
    }

    fun consumeMessage() {
        message = null
    }

    fun save(id: String?, title: String, description: String, start: Long, end: Long) {
        viewModelScope.launch {
            community.saveTrip(id, title, description, start, end)
            message = if (id == null) "Călătoria a fost creată." else "Călătoria a fost actualizată."
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            community.deleteTrip(id)
            message = "Călătoria a fost ștearsă. Postările ei au rămas."
        }
    }

    private fun TripEntity.toView(posts: List<Post>) =
        TripView(id, ownerKey, title, description, startDate, endDate, syncStatus != "SYNCED", postsOf(posts, id))

    private fun postsOf(posts: List<Post>, tripId: String) = posts
        .filter { it.trip_id == tripId && !it.isPrivate && (it.status == PostStatus.ACTIVE || it.user_id == currentUser.value?.ownerKey) }
        .sortedBy { it.visit_date.takeIf { d -> d > 0 } ?: Long.MAX_VALUE }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication
                TripsViewModel(app.container.postRepository, app.container.roleRepository, app.container.communityRepository)
            }
        }
    }
}

/** Calatoriile mele: lista, cu coperta, perioada si cate locuri; "Călătorie nouă". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(onBack: () -> Unit, onOpenTrip: (tripId: String, ownerId: String) -> Unit) {
    val viewModel = viewModel<TripsViewModel>(factory = TripsViewModel.Factory)
    val trips by viewModel.myTrips.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Călătoriile mele") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi") } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Călătorie nouă") }
            )
        }
    ) { padding ->
        if (trips.isEmpty()) {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Luggage, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Nicio călătorie încă", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Grupează postările unei excursii într-o călătorie: o vezi pe hartă ca traseu, cu statistici. Merge și offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(trips, key = { it.id }) { trip -> TripCard(trip) { onOpenTrip(trip.id, trip.ownerId) } }
            }
        }
    }

    if (creating) {
        TripDialog(
            title = "Călătorie nouă",
            showDescription = true,
            onDismiss = { creating = false },
            onSave = { name, description, start, end ->
                viewModel.save(null, name, description, start, end)
                creating = false
            }
        )
    }
}

@Composable
fun TripCard(trip: TripView, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp)) {
        if (trip.cover.isNotBlank()) PostPhoto(photo = trip.cover, height = 150.dp, rounded = false)
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trip.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (trip.pending) {
                    Icon(Icons.Default.CloudUpload, contentDescription = "Se trimite la server", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                }
            }
            if (trip.period.isNotBlank()) Text(trip.period, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${trip.posts.size} postări · ${trip.places} locuri" + if (trip.days > 0) " · ${trip.days} zile" else "",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/**
 * Pagina unei calatorii: traseul pe harta (locurile in ordinea vizitei), statistici si postarile.
 * Proprietarul o poate edita sau sterge (postarile raman).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    tripId: String,
    ownerId: String,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit
) {
    val viewModel = viewModel<TripsViewModel>(factory = TripsViewModel.Factory)
    LaunchedEffect(tripId) { viewModel.open(tripId, ownerId) }
    val trip by viewModel.opened.collectAsStateWithLifecycle()
    val user by viewModel.currentUser.collectAsStateWithLifecycle()
    val mine = trip != null && user != null && trip?.ownerId == user?.ownerKey
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(trip?.title ?: "Călătorie", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi") } },
                actions = {
                    if (mine) {
                        IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, contentDescription = "Editează călătoria") }
                        IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, contentDescription = "Șterge călătoria") }
                    }
                }
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) }
    ) { padding ->
        val current = trip
        if (current == null) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (viewModel.loadingRemote) "Se încarcă…" else "Călătoria nu este disponibilă (sau ești offline).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }
        val route = current.posts.filter { it.location.isSet }
            .map { RoutePoint(it.location.latitude, it.location.longitude, it.title.ifBlank { it.place }) }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                if (current.period.isNotBlank()) Text(current.period, style = MaterialTheme.typography.titleMedium)
                if (current.description.isNotBlank()) Text(current.description, style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TripStat("${current.posts.size}", "postări", Modifier.weight(1f))
                    TripStat("${current.places}", "locuri", Modifier.weight(1f))
                    TripStat(if (current.days > 0) "${current.days}" else "–", "zile", Modifier.weight(1f))
                    TripStat(if (current.averageRating > 0) "%.1f★".format(current.averageRating) else "–", "rating", Modifier.weight(1f))
                }
            }
            if (route.isNotEmpty()) {
                item {
                    Text("Traseul", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Card(shape = RoundedCornerShape(16.dp)) {
                        RouteMap(points = route, modifier = Modifier.fillMaxWidth().height(240.dp))
                    }
                }
            }
            item { Text("Locurile, în ordinea vizitei", style = MaterialTheme.typography.titleMedium) }
            if (current.posts.isEmpty()) {
                item {
                    Text(
                        if (mine) "Adaugă postări în această călătorie din formularul postării (câmpul „Călătorie”)."
                        else "Călătoria nu are încă postări publice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(current.posts, key = { it.id }) { post ->
                TripPostRow(index = current.posts.indexOf(post) + 1, post = post, onClick = { onOpenPost(post.id) })
            }
        }
    }

    trip?.let { current ->
        if (editing) {
            TripDialog(
                title = "Editează călătoria",
                initialName = current.title,
                initialDescription = current.description,
                initialStart = current.startDate,
                initialEnd = current.endDate,
                showDescription = true,
                onDismiss = { editing = false },
                onSave = { name, description, start, end ->
                    viewModel.save(current.id, name, description, start, end)
                    editing = false
                }
            )
        }
        if (deleting) {
            AlertDialog(
                onDismissRequest = { deleting = false },
                title = { Text("Ștergi călătoria?") },
                text = { Text("Postările nu se șterg: rămân în jurnalul tău, doar nu mai fac parte din „${current.title}”.") },
                confirmButton = {
                    Button(onClick = {
                        deleting = false
                        viewModel.delete(current.id)
                        onBack()
                    }) { Text("Șterge") }
                },
                dismissButton = { TextButton(onClick = { deleting = false }) { Text("Anulează") } }
            )
        }
    }
}

@Composable
private fun TripStat(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun TripPostRow(index: Int, post: Post, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
            Box(modifier = Modifier.size(64.dp)) {
                if (post.displayPhoto.isNotBlank()) PostPhoto(photo = post.displayPhoto, height = 64.dp)
                else Text("$index", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("$index. ${post.title.ifBlank { post.place.ifBlank { "Postare" } }}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (post.place.isNotBlank()) Text(post.place, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (post.rating > 0) RatingStars(post.rating, size = 14.dp)
                    if (post.visit_date > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(formatVisitDate(post.visit_date), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
