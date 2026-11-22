package com.example.myandroidapp.todo.ui.post

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.myandroidapp.maps.DEFAULT_LATITUDE
import com.example.myandroidapp.maps.DEFAULT_LONGITUDE
import com.example.myandroidapp.maps.OsmMap
import com.example.myandroidapp.maps.Place
import com.example.myandroidapp.maps.PlaceSearch
import com.example.myandroidapp.maps.currentDeviceLocation
import com.example.myandroidapp.todo.data.Location
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** O postare are loc doar daca are coordonate (0,0 = fara loc, ca la postarile vechi). */
val Location.isSet: Boolean get() = latitude != 0.0 || longitude != 0.0

/**
 * Alegerea locului unei postari, fara servicii platite:
 *  - cautare dupa nume (Geocoder din Android / OpenStreetMap Nominatim);
 *  - "Locația mea" (GPS-ul telefonului);
 *  - "Alege pe hartă" (harta OpenStreetMap pe tot ecranul, atingi locul).
 * Locul ales apare cu nume si o previzualizare pe harta; "Șterge" il scoate.
 */
@Composable
fun LocationPickerField(
    place: String,
    location: Location,
    enabled: Boolean,
    onLocationChange: (place: String, location: Location) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var showMapPicker by rememberSaveable { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun runSearch() {
        focusManager.clearFocus()
        searchJob?.cancel()
        searchJob = scope.launch {
            busy = true
            info = null
            results = PlaceSearch.search(context, query)
            if (results.isEmpty()) info = "Nu am găsit locul. Verifică internetul sau încearcă alt nume."
            busy = false
        }
    }

    fun useDeviceLocation() {
        scope.launch {
            busy = true
            info = "Se caută locația ta…"
            val here = currentDeviceLocation(context)
            if (here == null) {
                info = "Locația nu este disponibilă. Pornește GPS-ul (pe emulator: ⋮ → Location)."
            } else {
                val name = PlaceSearch.nameOf(context, here.latitude, here.longitude)
                onLocationChange(name ?: "Locația mea", Location(here.latitude, here.longitude))
                info = null
                results = emptyList()
            }
            busy = false
        }
    }

    val requestLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) useDeviceLocation()
        else info = "Fără permisiunea de locație poți căuta locul după nume sau îl poți alege pe hartă."
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Locația", style = MaterialTheme.typography.labelLarge)

        if (location.isSet) {
            Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                OsmMap(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    interactive = false,
                    markerTitle = place,
                    onClick = { if (enabled) showMapPicker = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            place.ifBlank { "Loc ales pe hartă" },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "%.4f, %.4f".format(location.latitude, location.longitude),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onLocationChange("", Location()) }, enabled = enabled) {
                        Icon(Icons.Default.Close, contentDescription = "Șterge locația")
                    }
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(if (location.isSet) "Caută alt loc" else "Caută locul (ex. Cheile Turzii)") },
            singleLine = true,
            enabled = enabled,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else if (query.isNotBlank()) {
                    TextButton(onClick = ::runSearch, enabled = enabled) { Text("Caută") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) runSearch() }),
            modifier = Modifier.fillMaxWidth()
        )

        if (results.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    results.forEachIndexed { index, result ->
                        if (index > 0) HorizontalDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled) {
                                    onLocationChange(result.name, Location(result.latitude, result.longitude))
                                    results = emptyList()
                                    query = ""
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(result.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = enabled && !busy,
                onClick = {
                    val granted = listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                    if (granted) useDeviceLocation()
                    else requestLocation.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Locația mea")
            }
            OutlinedButton(enabled = enabled, onClick = { showMapPicker = true }) {
                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Alege pe hartă")
            }
        }

        info?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }

    if (showMapPicker) {
        MapPickerDialog(
            initial = location.takeIf { it.isSet },
            onDismiss = { showMapPicker = false },
            onConfirm = { name, picked ->
                onLocationChange(name, picked)
                showMapPicker = false
            }
        )
    }
}

/** Harta pe tot ecranul: atingi locul, vezi numele lui, confirmi. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapPickerDialog(
    initial: Location?,
    onDismiss: () -> Unit,
    onConfirm: (place: String, location: Location) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var nameJob by remember { mutableStateOf<Job?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Alege locul pe hartă") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi")
                        }
                    }
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            when {
                                picked == null -> "Atinge harta în locul pe care l-ai vizitat."
                                resolving -> "Se caută numele locului…"
                                else -> name ?: "Loc ales pe hartă"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { picked?.let { onConfirm(name.orEmpty(), it) } },
                            enabled = picked != null && !resolving,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Confirmă locul") }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                OsmMap(
                    latitude = picked?.latitude ?: DEFAULT_LATITUDE,
                    longitude = picked?.longitude ?: DEFAULT_LONGITUDE,
                    zoom = if (picked != null) 14.0 else 6.5,
                    showMarker = picked != null,
                    modifier = Modifier.fillMaxSize(),
                    onTap = { lat, lon ->
                        picked = Location(lat, lon)
                        nameJob?.cancel()
                        nameJob = scope.launch {
                            resolving = true
                            name = PlaceSearch.nameOf(context, lat, lon)
                            resolving = false
                        }
                    }
                )
            }
        }
    }
}
