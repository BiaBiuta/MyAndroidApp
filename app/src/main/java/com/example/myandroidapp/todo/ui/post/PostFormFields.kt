package com.example.myandroidapp.todo.ui.post

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
    onPhotoPicked: (base64: String?) -> Unit
) {
    val context = LocalContext.current
    // uri-ul fisierului in care camera scrie poza (supravietuieste rotirii ecranului)
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    val pickFromGallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) onPhotoPicked(PhotoEncoder.encode(context.contentResolver, uri))
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri?.let(Uri::parse)
        if (saved && uri != null) onPhotoPicked(PhotoEncoder.encode(context.contentResolver, uri))
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
