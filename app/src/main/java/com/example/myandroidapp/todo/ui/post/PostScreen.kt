package com.example.myandroidapp.todo.ui.post

import android.content.ContentResolver
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.example.myandroidapp.R
import com.example.myandroidapp.auth.data.remote.User
import com.example.myandroidapp.core.Result
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.core.data.UserPreferences
import com.example.myandroidapp.core.data.UserPreferencesRepository
import com.example.myandroidapp.todo.data.Location
import com.example.myapplication.core.userPreferencesDataStore
import com.example.myapplication.notifications.createNotificationChannel
import com.example.myapplication.notifications.showSimpleNotification
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull.content
import java.io.InputStream
import androidx.compose.runtime.rememberCoroutineScope as rememberCoroutineScope1

class PostAddActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?,) {
        super.onCreate(savedInstanceState)
        setContent {
            PostAddScreen(
                onClose = {  }
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostAddScreen(onClose: () -> Unit) {
    val postViewModel = viewModel<PostViewModel>(factory = PostViewModel.Factory(""))
    val postUiState=postViewModel.uiState
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var photoPath by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var selectedLocation by rememberSaveable { mutableStateOf<Pair<Double, Double>?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var savingError by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope1() // Gestionăm corutinele dintr-un context Compose
    val userPreferencesRepository = UserPreferencesRepository(
        dataStore = context.userPreferencesDataStore
    )
    var user by rememberSaveable { mutableStateOf<User>(User("","",0)) }
    val markerState = rememberMarkerState(
        position = LatLng(selectedLocation?.first ?: 0.0, selectedLocation?.second ?: 0.0)
    )
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(markerState.position, 10f)
    }
    LaunchedEffect(Unit) {
        scope.launch {
            user = userPreferencesRepository.getUser()?:User("","",0)
        }
    }
    val selectImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        photoUri = uri
        photoPath = uri?.toString() ?: "" // Convertim Uri la string
    }
    var textInitialized by remember { mutableStateOf("") }

    LaunchedEffect (postUiState.submitResult){
        Log.d("ItemScreen", "Submit = ${postUiState.submitResult}");
        if(postUiState.submitResult is Result.Success){
            Log.d("ItemScreen", "Closing screen");
            onClose();
        }
    }
    //val context = LocalContext.current
    val channelId = "MyTestChannel"
    var notificationId = 0

    LaunchedEffect(Unit) {
        createNotificationChannel(channelId, context)
        Log.d(TAG,"am creat notification channel")

    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Add New Post") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Photo Selector
            Button(onClick = {
                selectImageLauncher.launch("image/*")
            }) {
                Text("Select Photo")
            }

            photoUri?.let {
                Image(
                    painter = rememberAsyncImagePainter(it),
                    contentDescription = "Selected Image",
                    modifier = Modifier
                        .size(150.dp)
                        .border(1.dp, Color.Gray)
                )
                Text("Photo Path: $photoPath") // Afișează calea imaginii
            }

            // Description Input
            BasicTextField(
                value = description,
                onValueChange = { description = it },
                textStyle = TextStyle(color = Color.Black),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .border(1.dp, Color.Gray)
                    .padding(8.dp)
            )

            // Location Selector
            Button(onClick = {
//                GoogleMap(
//                    cameraPositionState = cameraPositionState,
//                    onMapLongClick = {
//                        markerState.position = it
//                        lat = it.latitude
//                        lon = it.longitude
//                    },
//                ) {
//                    Marker(
//                        state = MarkerState(position = markerState.position),
//                        title = "User location title",
//                        snippet = "User location",
//                    )
//                }
             selectedLocation = Pair(47.0, 27.0) //Simulăm selecția locației
            }) {
                Text("Select Location")
            }
            selectedLocation?.let {
                Text("Selected Location: ${it.first}, ${it.second}")
            }

            val locationToSave = selectedLocation?.let {
                Location(it.first, it.second)
            } ?: Location()

            // Save Button
            Button(onClick = {
                saving = true
                savingError = null
                photoUri?.let { uri ->
                    scope.launch {
                        val base64Image = convertImageToBase64(context.contentResolver, uri)
                        if (base64Image != null) {
                            postViewModel.saveOrUpdateItem(base64Image, description, user.id.toString(), "","", "", locationToSave)
                            Log.d("PostAdd", "Saving post with photo=$base64Image, description=$description, location=$selectedLocation")

                        } else {
                            savingError = "Eroare la conversia imaginii"
                        }
                        saving = false
                    }
                }
                showSimpleNotification(
                    context,
                    channelId,
                    notificationId,
                    "Post add",
                    "${user.id} add a post"
                )
                notificationId += 1
            }) {
                Text("Save Post")
            }

            // Loading Indicator
            if (postUiState.loadResult is Result.Loading) {
                CircularProgressIndicator()
                return@Scaffold
            }
            if (postUiState.submitResult is Result.Loading) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) { LinearProgressIndicator() }
            }

        }
    }
}

// Functia de conversie a imaginii in base64
 fun convertImageToBase64(contentResolver: ContentResolver, imageUri: Uri): String? {
    return try {
        val inputStream: InputStream? = contentResolver.openInputStream(imageUri)
        val bytes = inputStream?.readBytes()
        inputStream?.close()

        if (bytes != null) {
            // Convertește byte array într-un șir base64
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.DEFAULT)
        } else {
            null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
