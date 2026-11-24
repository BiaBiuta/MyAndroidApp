package com.example.myandroidapp.todo.ui.post

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myandroidapp.notifications.createNotificationChannel
import com.example.myandroidapp.notifications.showSimpleNotification

class PostAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PostAddScreen(onClose = { finish() }) }
    }
}

private const val CHANNEL_ID = "MyTestChannel"

/**
 * Postare noua: titlu, text, poza (galerie sau camera), rating, data vizitei, locatia pe harta,
 * ciorna (vizibila doar autorului) sau postare privata (doar pe telefon, criptata).
 * Salvarea merge si fara internet: postarea apare imediat ("Pending") si se trimite singura.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostAddScreen(
    onClose: () -> Unit,
    isPrivateInitially: Boolean = false,
    // postare dintr-un loc inregistrat de tracker (Trackerele mele -> Postează)
    prefillPlace: String? = null,
    prefillLocation: com.example.myandroidapp.todo.data.Location? = null,
    prefillVisitDate: Long = 0
) {
    val viewModel = viewModel<PostViewModel>(factory = PostViewModel.Factory)
    val state = viewModel.state
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (isPrivateInitially) viewModel.onPrivateChange(true)
        if (prefillPlace != null && prefillLocation != null) viewModel.prefill(prefillPlace, prefillLocation, prefillVisitDate)
        createNotificationChannel(CHANNEL_ID, context)
    }
    // dupa salvare: mesajul (publicata / in coada / privata), apoi inapoi la lista
    LaunchedEffect(state.savedMessage) {
        val message = state.savedMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        if (!state.isPrivate && !state.isDraft) {
            showSimpleNotification(context, CHANNEL_ID, 1, "Postare nouă", state.title.ifBlank { "Ai adăugat o postare" })
        }
        onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isPrivate) "Postare privată nouă" else "Postare nouă") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text("Titlu / destinație") },
                placeholder = { Text("ex. Cheile Turzii") },
                singleLine = true,
                enabled = !state.saving,
                supportingText = { Text("${state.title.length}/${PostViewModel.MAX_TITLE}") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text("Impresii") },
                minLines = 3,
                enabled = !state.saving,
                supportingText = { Text("${state.description.length}/${PostViewModel.MAX_DESCRIPTION}") },
                modifier = Modifier.fillMaxWidth()
            )

            GalleryPickerField(
                photos = state.photos,
                enabled = !state.saving,
                onPhotosChange = viewModel::onPhotosChange,
                onError = viewModel::onPhotoError
            )
            // calatoria (albumul); postarile private nu fac parte din calatorii
            if (!state.isPrivate) {
                val trips by viewModel.trips.collectAsStateWithLifecycle()
                TripPickerField(
                    tripId = state.tripId,
                    trips = trips,
                    enabled = !state.saving,
                    onTripChange = viewModel::onTripChange,
                    onCreateTrip = viewModel::createTrip
                )
            }
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
                description = if (state.isDraft) "O vezi doar tu; o publici mai târziu din editare"
                else "Publicată în lista tuturor",
                checked = state.isDraft,
                enabled = !state.saving && !state.isPrivate,
                onCheckedChange = viewModel::onDraftChange
            )
            LabeledSwitch(
                title = "Postare privată",
                description = if (state.isPrivate) "Doar pe acest telefon, criptată, în secțiunea privată"
                else "Ajunge pe server și se sincronizează",
                checked = state.isPrivate,
                enabled = !state.saving,
                onCheckedChange = viewModel::onPrivateChange
            )

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose, enabled = !state.saving) { Text("Anulează") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = viewModel::save, enabled = !state.saving) {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Se salvează…")
                    } else {
                        Text(if (state.isDraft && !state.isPrivate) "Salvează ciorna" else "Salvează")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Poza aleasa -> Base64, MICSORATA si comprimata (vezi PhotoEncoder): pozele mari de la camera
 * nu mai depasesc limita de ~2 MB pe rand a SQLite (aplicatia se oprea la afisarea listei).
 */
fun convertImageToBase64(contentResolver: ContentResolver, imageUri: Uri): String? =
    PhotoEncoder.encode(contentResolver, imageUri)
