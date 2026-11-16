package com.example.myandroidapp.todo.ui.edit

import android.util.Log
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
import com.example.myandroidapp.todo.ui.post.PhotoPickerField
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
    val photo: String = "",
    val rating: Int = 0,
    val visitDate: Long = 0,
    val isDraft: Boolean = false,
    val error: String? = null,
    // mesaj final, afisat inainte de a reveni (ex. "Salvată pe telefon...")
    val savedMessage: String? = null
)

/**
 * Editarea unei postari proprii (Update din CRUD), si offline: modificarea se salveaza
 * pe telefon ("Pending") si PostRepository o trimite cand serverul e disponibil.
 * Doar autorul poate edita; regula e verificata aici (si pe server), nu doar in UI.
 */
class EditPostViewModel(
    private val postRepository: PostRepository,
    private val roleRepository: RoleRepository
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
                        photo = post.photo,
                        rating = post.rating,
                        visitDate = post.visit_date,
                        isDraft = post.status == PostStatus.DRAFT
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "load failed", e)
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

    fun onPhotoPicked(base64: String?) {
        uiState = if (base64 == null) uiState.copy(error = "Imaginea nu a putut fi citită.")
        else uiState.copy(photo = base64, error = null)
    }

    fun onRatingChange(value: Int) {
        uiState = uiState.copy(rating = value.coerceIn(0, 5))
    }

    fun onVisitDateChange(value: Long) {
        uiState = uiState.copy(visitDate = value)
    }

    fun onDraftChange(value: Boolean) {
        uiState = uiState.copy(isDraft = value)
    }

    private fun edited(original: Post) = original.copy(
        title = uiState.title.trim(),
        description = uiState.description.trim(),
        photo = uiState.photo,
        rating = uiState.rating,
        visit_date = uiState.visitDate,
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
        if (changed.title.isEmpty() && changed.description.isEmpty() && changed.photo.isEmpty()) {
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
                Log.w(TAG, "save failed", e)
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
                EditPostViewModel(app.container.postRepository, app.container.roleRepository)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editează postarea") },
                navigationIcon = {
                    IconButton(onClick = { onClose(null) }) {
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
                    PhotoPickerField(photo = state.photo, enabled = !state.saving, onPhotoPicked = viewModel::onPhotoPicked)
                    RatingField(rating = state.rating, enabled = !state.saving, onRatingChange = viewModel::onRatingChange)
                    VisitDateField(visitDate = state.visitDate, enabled = !state.saving, onDateChange = viewModel::onVisitDateChange)
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
                        TextButton(onClick = { onClose(null) }, enabled = !state.saving) { Text("Anulează") }
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
