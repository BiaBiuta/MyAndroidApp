package com.example.myandroidapp.todo.ui.edit

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.rememberAsyncImagePainter
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.auth.data.RoleRepository
import com.example.myandroidapp.todo.data.Post
import com.example.myandroidapp.todo.data.PostRepository
import com.example.myandroidapp.todo.ui.post.convertImageToBase64
import kotlinx.coroutines.launch

/** Starea ecranului de editare. */
data class EditPostUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    // postarea originala (null = negasita sau fara drept de editare)
    val post: Post? = null,
    val description: String = "",
    // fotografia (base64 / URL); se schimba doar daca utilizatorul alege alta
    val photo: String = "",
    val error: String? = null,
    // mesaj final, afisat inainte de a reveni (ex. "Salvată doar pe telefon")
    val savedMessage: String? = null
)

/**
 * Editarea unei postari proprii (Update din CRUD). Doar autorul poate edita;
 * regula e verificata aici, nu doar in UI (butonul "Editează").
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
                    post == null -> EditPostUiState(loading = false, error = "Postarea nu mai există.")
                    user == null || user.isBlocked || post.user_id != user.ownerKey ->
                        EditPostUiState(loading = false, error = "Puteți edita doar postările proprii.")
                    else -> EditPostUiState(
                        loading = false,
                        post = post,
                        description = post.description,
                        photo = post.photo
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "load failed", e)
                uiState = EditPostUiState(loading = false, error = "Postarea nu a putut fi încărcată.")
            }
        }
    }

    fun onDescriptionChange(value: String) {
        if (value.length <= MAX_DESCRIPTION) uiState = uiState.copy(description = value, error = null)
    }

    fun onPhotoPicked(base64: String?) {
        uiState = if (base64 == null) {
            uiState.copy(error = "Imaginea nu a putut fi citită.")
        } else {
            uiState.copy(photo = base64, error = null)
        }
    }

    val hasChanges: Boolean
        get() = uiState.post?.let { it.description != uiState.description.trim() || it.photo != uiState.photo } ?: false

    fun save() {
        val original = uiState.post ?: return
        val description = uiState.description.trim()
        if (description.isEmpty()) {
            uiState = uiState.copy(error = "Descrierea nu poate fi goală.")
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
                val result = postRepository.updatePost(
                    original.copy(description = description, photo = uiState.photo)
                )
                uiState = uiState.copy(
                    saving = false,
                    savedMessage = when (result) {
                        PostRepository.UpdateResult.SAVED -> "Postarea a fost actualizată."
                        PostRepository.UpdateResult.SAVED_LOCALLY_ONLY ->
                            "Postarea a fost actualizată pe telefon (serverul nu este disponibil acum)."
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "save failed", e)
                uiState = uiState.copy(saving = false, error = "Modificările nu au putut fi salvate.")
            }
        }
    }

    companion object {
        private const val TAG = "EditPostViewModel"
        const val MAX_DESCRIPTION = 1000

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
    val context = LocalContext.current

    LaunchedEffect(postId) { viewModel.load(postId) }
    LaunchedEffect(state.savedMessage) {
        state.savedMessage?.let { onClose(it) }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) viewModel.onPhotoPicked(convertImageToBase64(context.contentResolver, uri))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editează postarea") },
                navigationIcon = {
                    IconButton(onClick = { onClose(null) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Înapoi")
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
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.post == null -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
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
                        .padding(16.dp)
                ) {
                    OutlinedTextField(
                        value = state.description,
                        onValueChange = viewModel::onDescriptionChange,
                        label = { Text("Descriere") },
                        minLines = 4,
                        enabled = !state.saving,
                        isError = state.description.isBlank(),
                        supportingText = {
                            Text("${state.description.length}/${EditPostViewModel.MAX_DESCRIPTION}")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    PostPhotoPreview(state.photo)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { pickImage.launch("image/*") },
                        enabled = !state.saving
                    ) { Text(if (state.photo.isBlank()) "Adaugă o fotografie" else "Schimbă fotografia") }

                    state.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    if (state.post.isNotSaved || state.post.id.startsWith("local-")) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Postarea nu a fost încă trimisă la server; va fi trimisă cu modificările la următoarea sincronizare.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onClose(null) }, enabled = !state.saving) { Text("Anulează") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = viewModel::save, enabled = !state.saving && state.description.isNotBlank()) {
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

@Composable
private fun PostPhotoPreview(photo: String) {
    if (photo.isBlank()) return
    val modifier = Modifier
        .fillMaxWidth()
        .height(200.dp)
        .clip(RoundedCornerShape(8.dp))
    val base64Data = photo.substringAfter("base64,", "")
    if (base64Data.isNotEmpty()) {
        val bitmap = remember(photo) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = "Fotografia postării", modifier = modifier, contentScale = ContentScale.Crop)
        }
    } else {
        Image(
            painter = rememberAsyncImagePainter(photo),
            contentDescription = "Fotografia postării",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}
