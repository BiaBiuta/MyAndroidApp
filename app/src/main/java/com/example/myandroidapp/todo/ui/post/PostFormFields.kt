package com.example.myandroidapp.todo.ui.post

import androidx.compose.runtime.remember
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.myandroidapp.todo.ui.posts.PostPhoto
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Componentele comune ale formularelor de adaugare si editare a unei postari
 * (titlu si text raman OutlinedTextField-uri obisnuite in fiecare ecran).
 */

/** Poza postarii: previzualizare + "Din galerie" / "Fă o poză" (camera). */
@Composable
fun PhotoPickerField(
    photo: String,
    enabled: Boolean,
    // calea fisierului salvat (null = poza nu a putut fi citita)
    onPhotoPicked: (photo: String?) -> Unit
) {
    val context = LocalContext.current
    // uri-ul fisierului in care camera scrie poza (supravietuieste rotirii ecranului)
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    val pickFromGallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        // poza devine un fisier al aplicatiei (calea lui merge mai departe in formular)
        if (uri != null) onPhotoPicked(PhotoEncoder.saveToFile(context, uri))
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri?.let(Uri::parse)
        if (saved && uri != null) onPhotoPicked(PhotoEncoder.saveToFile(context, uri))
    }
    fun launchCamera() {
        val uri = newCameraUri(context)
        cameraUri = uri.toString()
        takePicture.launch(uri)
    }
    // CAMERA e declarata in manifest, deci trebuie acordata inainte de TakePicture
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        PostPhoto(photo = photo)
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFromGallery.launch("image/*") }, enabled = enabled) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Din galerie")
            }
            OutlinedButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    if (granted) launchCamera() else requestCamera.launch(Manifest.permission.CAMERA)
                },
                enabled = enabled
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fă o poză")
            }
        }
    }
}

/** Fisier temporar pentru camera, partajat prin FileProvider (res/xml/file_paths.xml). */
private fun newCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "poza_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** Rating 1-5 stele; TalkBack citeste "3 din 5 stele". Atingerea stelei selectate o anuleaza. */
@Composable
fun RatingField(rating: Int, enabled: Boolean, onRatingChange: (Int) -> Unit) {
    Column {
        Text("Rating", style = MaterialTheme.typography.labelLarge)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics { contentDescription = "Rating: $rating din 5 stele" }
        ) {
            for (star in 1..5) {
                IconButton(onClick = { onRatingChange(if (rating == star) 0 else star) }, enabled = enabled) {
                    Icon(
                        imageVector = if (star <= rating) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "$star stele",
                        tint = if (star <= rating) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Data vizitei: buton care deschide calendarul sistemului. */
@Composable
fun VisitDateField(visitDate: Long, enabled: Boolean, onDateChange: (Long) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(
        enabled = enabled,
        onClick = {
            val calendar = Calendar.getInstance().apply {
                if (visitDate > 0) timeInMillis = visitDate
            }
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    val picked = Calendar.getInstance().apply {
                        clear()
                        set(year, month, day, 12, 0)
                    }
                    onDateChange(picked.timeInMillis)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).apply {
                // o vizita nu poate fi in viitor
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }
    ) {
        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (visitDate > 0) "Data vizitei: ${formatVisitDate(visitDate)}" else "Alege data vizitei")
    }
}

fun formatVisitDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

/** Comutator cu titlu si explicatie (ciorna, postare privata). */
@Composable
fun LabeledSwitch(title: String, description: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Rezultatul unei salvari, ca mesaj pentru utilizator. */
fun writeResultMessage(result: com.example.myandroidapp.todo.data.PostRepository.WriteResult, sentMessage: String): String =
    when (result) {
        com.example.myandroidapp.todo.data.PostRepository.WriteResult.Sent -> sentMessage
        com.example.myandroidapp.todo.data.PostRepository.WriteResult.Queued ->
            "Salvată pe telefon. Se trimite automat când revine conexiunea."
        is com.example.myandroidapp.todo.data.PostRepository.WriteResult.Failed ->
            "Salvată pe telefon, dar serverul a refuzat-o: ${result.message}"
        is com.example.myandroidapp.todo.data.PostRepository.WriteResult.Resolved -> result.message
    }

/**
 * Pozele postarii (max. [max]): miniaturi cu "Principală" pe prima, X pentru stergere, atingere pentru
 * a face o poza principala; adaugare din galerie (mai multe deodata) sau cu camera.
 */
@Composable
fun GalleryPickerField(
    photos: List<String>,
    enabled: Boolean,
    onPhotosChange: (List<String>) -> Unit,
    onError: (String) -> Unit,
    max: Int = 6
) {
    val context = LocalContext.current
    val currentPhotos by androidx.compose.runtime.rememberUpdatedState(photos)
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    fun add(paths: List<String?>) {
        val ok = paths.filterNotNull()
        if (ok.size < paths.size) onError("Una dintre poze nu a putut fi citită.")
        onPhotosChange((currentPhotos + ok).take(max))
    }

    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val room = max - currentPhotos.size
        if (uris.size > room) onError("O postare poate avea cel mult $max poze.")
        add(uris.take(room).map { PhotoEncoder.saveToFile(context, it) })
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri?.let(Uri::parse)
        if (saved && uri != null) add(listOf(PhotoEncoder.saveToFile(context, uri)))
    }
    fun launchCamera() {
        val uri = newCameraUri(context)
        cameraUri = uri.toString()
        takePicture.launch(uri)
    }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Poze (${photos.size}/$max)", style = MaterialTheme.typography.labelLarge)
        if (photos.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                photos.forEachIndexed { index, photo ->
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = enabled && index > 0) {
                                // atingerea unei poze o face principala (coperta postarii)
                                onPhotosChange(listOf(photo) + photos.filterIndexed { i, _ -> i != index })
                            }
                    ) {
                        com.example.myandroidapp.todo.ui.posts.PostPhoto(photo = photo, height = 104.dp, rounded = false)
                        if (index == 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = RoundedCornerShape(bottomEnd = 8.dp),
                                modifier = Modifier.align(Alignment.TopStart)
                            ) {
                                Text("Principală", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp)
                        ) {
                            IconButton(onClick = { onPhotosChange(photos.filterIndexed { i, _ -> i != index }) }, enabled = enabled) {
                                Icon(Icons.Default.Close, contentDescription = "Scoate poza ${index + 1}", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            if (photos.size > 1) {
                Text(
                    "Atinge o poză ca s-o faci principală.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickMany.launch("image/*") }, enabled = enabled && photos.size < max) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Din galerie")
            }
            OutlinedButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    if (granted) launchCamera() else requestCamera.launch(Manifest.permission.CAMERA)
                },
                enabled = enabled && photos.size < max
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fă o poză")
            }
        }
    }
}

/**
 * Calatoria (albumul) postarii: lista calatoriilor proprii + "Fără călătorie" + "Călătorie nouă…".
 * Calatoria noua se creeaza si offline (se trimite la server inaintea postarii).
 */
@Composable
fun TripPickerField(
    tripId: String,
    trips: List<com.example.myandroidapp.community.data.TripEntity>,
    enabled: Boolean,
    onTripChange: (String) -> Unit,
    onCreateTrip: (title: String, startDate: Long, endDate: Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val selected = trips.find { it.id == tripId }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Călătorie", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Luggage, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(selected?.title ?: "Fără călătorie", modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Fără călătorie") },
                    onClick = { onTripChange(""); expanded = false }
                )
                trips.forEach { trip ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(trip.title) },
                        onClick = { onTripChange(trip.id); expanded = false }
                    )
                }
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Călătorie nouă…") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = { expanded = false; creating = true }
                )
            }
        }
    }
    if (creating) {
        TripDialog(
            title = "Călătorie nouă",
            onDismiss = { creating = false },
            onSave = { name, _, start, end ->
                onCreateTrip(name, start, end)
                creating = false
            }
        )
    }
}

/** Dialogul de creare / editare a unei calatorii: nume, descriere (optional), perioada. */
@Composable
fun TripDialog(
    title: String,
    initialName: String = "",
    initialDescription: String = "",
    initialStart: Long = 0,
    initialEnd: Long = 0,
    showDescription: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, start: Long, end: Long) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    var start by rememberSaveable { mutableStateOf(initialStart) }
    var end by rememberSaveable { mutableStateOf(initialEnd) }
    val invalidDates = start > 0 && end > 0 && end < start
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 80) name = it },
                    label = { Text("Nume (ex. Transilvania, mai 2026)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (showDescription) {
                    androidx.compose.material3.OutlinedTextField(
                        value = description,
                        onValueChange = { if (it.length <= 1000) description = it },
                        label = { Text("Descriere (opțional)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                DateButton("Început", start) { start = it }
                DateButton("Sfârșit", end) { end = it }
                if (invalidDates) {
                    Text("Sfârșitul nu poate fi înaintea începutului.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = { onSave(name.trim(), description.trim(), start, end) },
                enabled = name.isNotBlank() && !invalidDates
            ) { Text("Salvează") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Anulează") } }
    )
}

@Composable
private fun DateButton(label: String, value: Long, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val calendar = Calendar.getInstance().apply { if (value > 0) timeInMillis = value }
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    onChange(Calendar.getInstance().apply { clear(); set(year, month, day, 12, 0) }.timeInMillis)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (value > 0) "$label: ${formatVisitDate(value)}" else "$label: alege data")
    }
}
