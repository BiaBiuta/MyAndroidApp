package com.example.myandroidapp.todo.ui.post

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.auth.data.RoleRepository
import com.example.myandroidapp.auth.data.local.isAdmin
import com.example.myandroidapp.auth.data.local.isGuest
import com.example.myandroidapp.core.data.remote.toUserMessage
import com.example.myandroidapp.todo.data.Location
import com.example.myandroidapp.todo.data.Post
import com.example.myandroidapp.todo.data.PostRepository
import com.example.myandroidapp.todo.data.PostStatus
import kotlinx.coroutines.launch

/** Starea formularului "Postare nouă". */
data class PostFormState(
    val title: String = "",
    val description: String = "",
    // Base64 ("data:image/jpeg;base64,...") deja micsorat de PhotoEncoder; "" = fara poza
    val photo: String = "",
    val rating: Int = 0,
    val visitDate: Long = System.currentTimeMillis(),
    // locul vizitat (OpenStreetMap): nume + coordonate; Location() = fara loc
    val place: String = "",
    val location: Location = Location(),
    // ciorna: vizibila doar autorului, pana o publica
    val isDraft: Boolean = false,
    // postare privata: doar pe telefon, criptata, in sectiunea privata
    val isPrivate: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    // mesajul de dupa salvare (ex. "Salvată pe telefon..."); ecranul se inchide cand apare
    val savedMessage: String? = null
)

/**
 * Postare noua. ViewModel-ul nu stie nimic de retea: PostRepository salveaza intai pe telefon
 * si trimite la server (sau pune in coada daca serverul nu e disponibil).
 */
class PostViewModel(
    private val postRepository: PostRepository,
    private val roleRepository: RoleRepository
) : ViewModel() {

    var state by mutableStateOf(PostFormState())
        private set

    fun onTitleChange(value: String) {
        if (value.length <= MAX_TITLE) state = state.copy(title = value, error = null)
    }

    fun onDescriptionChange(value: String) {
        if (value.length <= MAX_DESCRIPTION) state = state.copy(description = value, error = null)
    }

    fun onPhotoPicked(photo: String?) {
        state = if (photo == null) state.copy(error = "Poza nu a putut fi citită. Încercați alta.")
        else state.copy(photo = photo, error = null)
    }

    fun onRatingChange(value: Int) {
        state = state.copy(rating = value.coerceIn(0, 5))
    }

    fun onVisitDateChange(value: Long) {
        state = state.copy(visitDate = value)
    }

    fun onLocationChange(place: String, location: Location) {
        state = state.copy(place = place, location = location, error = null)
    }

    private var prefilled = false

    /** Postare noua dintr-un loc trimis de tracker: locul, coordonatele si data vizitei (o singura data). */
    fun prefill(place: String, location: Location, visitDate: Long) {
        if (prefilled) return
        prefilled = true
        state = state.copy(
            title = state.title.ifBlank { place.substringBefore(",").take(MAX_TITLE) },
            place = place,
            location = location,
            visitDate = if (visitDate > 0) visitDate else state.visitDate
        )
    }

    fun onDraftChange(value: Boolean) {
        state = state.copy(isDraft = value)
    }

    fun onPrivateChange(value: Boolean) {
        state = state.copy(isPrivate = value)
    }

    fun save() {
        val form = state
        val title = form.title.trim()
        val description = form.description.trim()
        if (title.isEmpty() && description.isEmpty() && form.photo.isEmpty()) {
            state = form.copy(error = "Adăugați cel puțin un titlu, un text sau o poză.")
            return
        }
        viewModelScope.launch {
            state = state.copy(saving = true, error = null)
            try {
                // verificare si la salvare, nu doar in UI: doar USER publica postari
                val user = roleRepository.currentUserOnce()
                val reason = when {
                    user == null -> "Conectați-vă pentru a publica postări."
                    user.isAdmin -> "Administratorii nu pot publica postări."
                    user.isGuest -> "Vizitatorii anonimi nu pot publica postări. Creați un cont."
                    user.isBlocked -> RoleRepository.BLOCKED_MESSAGE
                    else -> null
                }
                if (reason != null || user == null) {
                    state = state.copy(saving = false, error = reason)
                    return@launch
                }
                val post = Post(
                    title = title,
                    description = description,
                    rating = form.rating,
                    visit_date = form.visitDate,
                    status = if (form.isDraft) PostStatus.DRAFT else PostStatus.ACTIVE,
                    place = form.place.trim(),
                    location = form.location,
                    // ownerKey = id-ul de pe server; postarile proprii se recunosc dupa el
                    user_id = user.ownerKey,
                    user_first_name = user.email.substringBefore("@"),
                    created_at = com.example.myandroidapp.core.isoNow()
                ).withPhoto(form.photo) // fisierul pozei: se urca pe server inaintea postarii
                val message = if (form.isPrivate) {
                    postRepository.savePrivate(post.copy(isPrivate = true))
                    "Postarea a fost salvată în secțiunea privată."
                } else {
                    writeResultMessage(
                        postRepository.createPost(post),
                        if (form.isDraft) "Ciorna a fost salvată (o vezi doar tu)." else "Postarea a fost publicată."
                    )
                }
                state = state.copy(saving = false, savedMessage = message)
            } catch (e: Exception) {
                Log.w(TAG, "save failed", e)
                state = state.copy(saving = false, error = e.toUserMessage("Postarea nu a putut fi salvată."))
            }
        }
    }

    companion object {
        private const val TAG = "PostViewModel"
        const val MAX_TITLE = 120
        const val MAX_DESCRIPTION = 2000

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
                PostViewModel(app.container.postRepository, app.container.roleRepository)
            }
        }
    }
}
