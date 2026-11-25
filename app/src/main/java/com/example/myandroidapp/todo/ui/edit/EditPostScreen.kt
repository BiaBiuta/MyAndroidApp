package com.example.myandroidapp.todo.ui.edit

import kotlinx.coroutines.flow.stateIn
import com.example.myandroidapp.core.logging.SafeLog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import com.example.myandroidapp.core.data.remote.toUserMessage
import com.example.myandroidapp.todo.data.Post
import com.example.myandroidapp.todo.data.PostRepository
import com.example.myandroidapp.todo.data.PostStatus
import com.example.myandroidapp.todo.data.SyncStatus
import com.example.myandroidapp.todo.ui.post.LabeledSwitch
import com.example.myandroidapp.todo.ui.post.LocationPickerField
import com.example.myandroidapp.todo.data.Location
import com.example.myandroidapp.todo.ui.post.GalleryPickerField
import com.example.myandroidapp.todo.ui.post.TripPickerField
import com.example.myandroidapp.todo.ui.post.RatingField
import com.example.myandroidapp.todo.ui.post.VisitDateField
import com.example.myandroidapp.todo.ui.post.writeResultMessage
import kotlinx.coroutines.launch

/** Starea ecranului de editare. */
data class EditPostUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    // postarea originala (null = negasita sau fara drept de editare)
    val post: Post? = null,
    val title: String = "",
    val description: String = "",
    // fotografia (Base64); se schimba doar daca utilizatorul alege alta
    // pozele (prima = principala): adrese de pe server sau fisiere noi, inca neurcate
    val photos: List<String> = emptyList(),
    val tripId: String = "",
    val rating: Int = 0,
    val visitDate: Long = 0,
    val place: String = "",
    val location: Location = Location(),
    val isDraft: Boolean = false,
    val error: String? = null,
    // mesaj final, afisat inainte de a reveni (ex. "Salvată pe telefon...")
    val savedMessage: String? = null
)

/** Ceva din formular difera de postarea incarcata (pentru confirmarea la iesire). */
val EditPostUiState.hasChanges: Boolean
    get() {
        val original = post ?: return false
        return title != original.title || description != original.description || photos != original.allPhotos ||
                tripId != original.trip_id || rating != original.rating || visitDate != original.visit_date ||
                place != original.place || location != original.location ||
                isDraft != (original.status == PostStatus.DRAFT)
    }

/**
 * Editarea unei postari proprii (Update din CRUD), si offline: modificarea se salveaza
 * pe telefon ("Pending") si PostRepository o trimite cand serverul e disponibil.
 * Doar autorul poate edita; regula e verificata aici (si pe server), nu doar in UI.
 */
class EditPostViewModel(
    private val postRepository: PostRepository,
    private val roleRepository: RoleRepository,
    private val community: com.example.myandroidapp.community.data.CommunityRepository
) : ViewModel() {

    var uiState by mutableStateOf(EditPostUiState())
        private set

    private var loadedId: String? = null

    fun load(postId: String) {
        if (loadedId == postId) return
        loadedId = postId
        viewModelScope.launch {
            uiState = EditPostUiState(loading = true)
            try {
                val user = roleRepository.currentUserOnce()
                val post = postRepository.findPost(postId)
                uiState = when {
                    post == null || post.isDeleted -> EditPostUiState(loading = false, error = "Postarea nu mai există.")
                    user == null || user.isBlocked || post.user_id != user.ownerKey ->
                        EditPostUiState(loading = false, error = "Puteți edita doar postările proprii.")
                    else -> EditPostUiState(
                        loading = false,
                        post = post,
                        title = post.title,
                        description = post.description,
                        photos = post.allPhotos,
                        tripId = post.trip_id,
                        rating = post.rating,
                        visitDate = post.visit_date,
                        place = post.place,
                        location = post.location,
                        isDraft = post.status == PostStatus.DRAFT
                    )
                }
            } catch (e: Exception) {
                SafeLog.w(TAG, "load failed", e)
                uiState = EditPostUiState(loading = false, error = "Postarea nu a putut fi încărcată.")
            }
        }
    }

    fun onTitleChange(value: String) {
        if (value.length <= MAX_TITLE) uiState = uiState.copy(title = value, error = null)
    }

    fun onDescriptionChange(value: String) {
        if (value.length <= MAX_DESCRIPTION) uiState = uiState.copy(description = value, error = null)
    }

    fun onPhotosChange(photos: List<String>) {
        uiState = uiState.copy(photos = photos.take(6), error = null)
    }

    fun onPhotoError(message: String) {
        uiState = uiState.copy(error = message)
    }

    /** Calatoriile proprii, pentru alegerea albumului. */
    val trips = community.myTrips.stateIn(
        viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    fun onTripChange(id: String) {
        uiState = uiState.copy(tripId = id)
    }

    fun createTrip(title: String, startDate: Long, endDate: Long) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val trip = community.saveTrip(null, title, "", startDate, endDate)
            uiState = uiState.copy(tripId = trip.id)
        }
    }

    fun onRatingChange(value: Int) {
        uiState = uiState.copy(rating = value.coerceIn(0, 5))
    }

    fun onVisitDateChange(value: Long) {
        uiState = uiState.copy(visitDate = value)
    }

    fun onLocationChange(place: String, location: Location) {
        uiState = uiState.copy(place = place, location = location, error = null)
    }

    fun onDraftChange(value: Boolean) {
        uiState = uiState.copy(isDraft = value)
    }

    // poza: fisier nou (se urca la sincronizare), cea de pe server sau cea veche (Base64)
    private fun edited(original: Post) = original.withPhotos(uiState.photos).copy(
        trip_id = uiState.tripId,
        title = uiState.title.trim(),
        description = uiState.description.trim(),
        rating = uiState.rating,
        visit_date = uiState.visitDate,
        place = uiState.place.trim(),
        location = uiState.location,
        status = if (uiState.isDraft) PostStatus.DRAFT else PostStatus.ACTIVE
    )

    val hasChanges: Boolean
        get() = uiState.post?.let { original ->
            // o postare refuzata de server ("Sincronizare esuata") se poate retrimite si neschimbata
            edited(original) != original || original.syncStatus == SyncStatus.FAILED
        } ?: false

    fun save() {
        val original = uiState.post ?: return
        val changed = edited(original)
        if (changed.title.isEmpty() && changed.description.isEmpty() && changed.displayPhoto.isEmpty()) {
            uiState = uiState.copy(error = "Postarea trebuie să aibă un titlu, un text sau o poză.")
            return
        }
        if (!hasChanges) {
            uiState = uiState.copy(savedMessage = "Nicio modificare de salvat.")
            return
        }
        viewModelScope.launch {
            uiState = uiState.copy(saving = true, error = null)
            try {
                // verificam din nou dreptul (rolul / contul se poate schimba intre timp)
                val user = roleRepository.currentUserOnce()
                if (user == null || user.isBlocked || original.user_id != user.ownerKey) {
                    uiState = uiState.copy(saving = false, error = "Puteți edita doar postările proprii.")
                    return@launch
                }
                val published = original.status == PostStatus.DRAFT && changed.status == PostStatus.ACTIVE
                val result = postRepository.updatePost(changed)
                uiState = uiState.copy(
                    saving = false,
                    savedMessage = writeResultMessage(
                        result,
                        if (published) "Ciorna a fost publicată." else "Postarea a fost actualizată."
                    )
                )
            } catch (e: Exception) {
                SafeLog.w(TAG, "save failed", e)
                uiState = uiState.copy(saving = false, error = e.toUserMessage("Modificările nu au putut fi salvate."))
            }
        }
    }

    companion object {
        private const val TAG = "EditPostViewModel"
        const val MAX_TITLE = 120
        const val MAX_DESCRIPTION = 2000

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
                EditPostViewModel(app.container.postRepository, app.container.roleRepository, app.container.communityRepository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPostScreen(postId: String, onClose: (message: String?) -> Unit) {
    val viewModel = viewModel<EditPostViewModel>(factory = EditPostViewModel.Factory)
    val state = viewModel.uiState

    LaunchedEffect(postId) { viewModel.load(postId) }
    LaunchedEffect(state.savedMessage) {
        state.savedMessage?.let { onClose(it) }
    }

    // iesirea cu modificari nesalvate cere confirmare (si butonul Inapoi al telefonului)
    val hasChanges = state.hasChanges
    var confirmDiscard by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val requestClose: () -> Unit = {
        if (hasChanges && !state.saving && state.savedMessage == null) confirmDiscard = true else onClose(null)
    }
    androidx.activity.compose.BackHandler(enabled = hasChanges && !state.saving && state.savedMessage == null) {
        confirmDiscard = true
    }
    if (confirmDiscard) {
        com.example.myandroidapp.todo.ui.post.DiscardChangesDialog(
            onKeepEditing = { confirmDiscard = false },
            onDiscard = {
                confirmDiscard = false
                onClose(null)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editează postarea") },
                navigationIcon = {
                    IconButton(onClick = requestClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val post = state.post
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                post == null -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(state.error ?: "Postarea nu a fost găsită.", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { onClose(null) }) { Text("Înapoi") }
                }
                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // de ce a esuat sincronizarea (ex. date refuzate de server) -> utilizatorul corecteaza
                    if (post.syncStatus == SyncStatus.FAILED) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(
                                "Sincronizarea a eșuat: ${post.syncError.ifBlank { "serverul a refuzat postarea." }} " +
                                        "Corectați postarea și salvați din nou.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = viewModel::onTitleChange,
                        label = { Text("Titlu / destinație") },
                        singleLine = true,
                        enabled = !state.saving,
                        supportingText = { Text("${state.title.length}/${EditPostViewModel.MAX_TITLE}") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.description,
                        onValueChange = viewModel::onDescriptionChange,
                        label = { Text("Impresii") },
                        minLines = 3,
                        enabled = !state.saving,
                        supportingText = { Text("${state.description.length}/${EditPostViewModel.MAX_DESCRIPTION}") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    GalleryPickerField(
                        photos = state.photos,
                        enabled = !state.saving,
                        onPhotosChange = viewModel::onPhotosChange,
                        onError = viewModel::onPhotoError
                    )
                    val trips by viewModel.trips.collectAsStateWithLifecycle()
                    TripPickerField(
                        tripId = state.tripId,
                        trips = trips,
                        enabled = !state.saving,
                        onTripChange = viewModel::onTripChange,
                        onCreateTrip = viewModel::createTrip
                    )
                    RatingField(rating = state.rating, enabled = !state.saving, onRatingChange = viewModel::onRatingChange)
                    VisitDateField(visitDate = state.visitDate, enabled = !state.saving, onDateChange = viewModel::onVisitDateChange)
                    LocationPickerField(
                        place = state.place,
                        location = state.location,
                        enabled = !state.saving,
                        onLocationChange = viewModel::onLocationChange
                    )
                    HorizontalDivider()
                    LabeledSwitch(
                        title = "Ciornă",
                        description = if (state.isDraft) "O vezi doar tu. Oprește comutatorul ca să o publici."
                        else "Publicată în lista tuturor",
                        checked = state.isDraft,
                        enabled = !state.saving,
                        onCheckedChange = viewModel::onDraftChange
                    )
                    if (post.isNotSaved && post.syncStatus == SyncStatus.PENDING) {
                        Text(
                            "Ultimele modificări nu au ajuns încă pe server; se trimit automat când revine conexiunea.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = requestClose, enabled = !state.saving) { Text("Anulează") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = viewModel::save, enabled = !state.saving) {
                            if (state.saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Salvează")
                        }
                    }
                }
            }
        }
    }
}
