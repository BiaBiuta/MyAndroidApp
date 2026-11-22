package com.example.myandroidapp.iot.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myandroidapp.iot.data.DeviceRepository
import com.example.myandroidapp.iot.data.local.DeviceStatus
import com.example.myandroidapp.iot.data.local.PairedDeviceEntity
import com.example.myandroidapp.iot.data.remote.DeviceVisit
import com.example.myandroidapp.iot.update.UpdateCheck
import com.example.myandroidapp.iot.update.UpdateManifest
import com.example.myandroidapp.todo.ui.posts.formatPostDate

/**
 * Trackerele mele (doar USER): asocierea cu cod, starea fiecarui tracker, locurile trimise de el
 * (din care poti crea o postare), actualizarile verificate si revocarea unui tracker pierdut.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    onCreatePost: (place: String, latitude: Double, longitude: Double, visitedAt: Long) -> Unit
) {
    val viewModel = viewModel<DevicesViewModel>(factory = DevicesViewModel.Factory)
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trackerele mele") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizează starea")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::openPairing,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Asociază un tracker") }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (viewModel.refreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (devices.isEmpty()) {
                EmptyDevices()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(devices, key = { it.id }) { device ->
                        DeviceCard(device = device, onClick = { viewModel.open(device) })
                    }
                }
            }
        }
    }

    viewModel.pairing?.let { PairingDialog(viewModel, it) }
    viewModel.detail?.let { detail ->
        viewModel.selectedDevice()?.let { device ->
            DeviceDetailDialog(viewModel, device, detail, onCreatePost)
        }
    }
}

@Composable
private fun EmptyDevices() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Router, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Niciun tracker asociat", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Trackerul WanderLog îți trimite locurile prin care treci, ca să le transformi ușor în postări. " +
                    "Asocierea se face cu un cod afișat pe tracker.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DeviceCard(device: PairedDeviceEntity, onClick: () -> Unit) {
    val revoked = device.status == DeviceStatus.REVOKED
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (revoked) Icons.Default.Block else Icons.Default.Router,
                contentDescription = null,
                tint = if (revoked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${device.id} · ${device.model} · firmware ${device.firmware}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusChip(
                text = if (revoked) "Revocat" else "Activ",
                icon = if (revoked) Icons.Default.Block else Icons.Default.VerifiedUser,
                error = revoked
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, icon: ImageVector, error: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ---------------------------------------------------------------------------
// Asocierea
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingDialog(viewModel: DevicesViewModel, ui: PairingUi) {
    Dialog(onDismissRequest = viewModel::closePairing, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Asociază un tracker") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::closePairing) { Icon(Icons.Default.Close, contentDescription = "Închide") }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                StepIndicator(ui.step)
                when (ui.step) {
                    PairStep.ADDRESS -> {
                        Text(
                            "1. Pornește modul de asociere pe tracker: în consola lui apasă „p” și Enter.\n" +
                                    "2. Introdu adresa trackerului din rețeaua locală.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = ui.host,
                            onValueChange = viewModel::onHostChange,
                            label = { Text("Adresa trackerului") },
                            supportingText = { Text("Pe emulator: ${DeviceRepository.DEFAULT_HOST}") },
                            singleLine = true,
                            enabled = !ui.busy,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = ui.name,
                            onValueChange = viewModel::onNameChange,
                            label = { Text("Nume (opțional)") },
                            placeholder = { Text("ex. Trackerul din rucsac") },
                            singleLine = true,
                            enabled = !ui.busy,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = viewModel::startPairing,
                            enabled = !ui.busy && ui.host.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { BusyText(ui.busy, "Conectează-te la tracker") }
                    }

                    PairStep.CODE -> {
                        ui.info?.let {
                            Text("Conectat la ${it.name} (${it.deviceId}, firmware ${it.firmware}).", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            "Pe ecranul trackerului (consola lui) a apărut un cod de 6 cifre. Tastează-l aici. " +
                                    "Codul nu circulă prin rețea: așa știm că vorbești chiar cu trackerul tău.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = ui.code,
                            onValueChange = viewModel::onCodeChange,
                            label = { Text("Codul de pe tracker") },
                            singleLine = true,
                            enabled = !ui.busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Codul de 6 cifre de pe tracker" }
                        )
                        Button(
                            onClick = viewModel::confirmCode,
                            enabled = !ui.busy && ui.code.length == 6,
                            modifier = Modifier.fillMaxWidth()
                        ) { BusyText(ui.busy, "Confirmă asocierea") }
                    }
                }
                ui.error?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(step: PairStep) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(PairStep.ADDRESS to "Conectare", PairStep.CODE to "Cod de pe tracker").forEachIndexed { index, (s, label) ->
            val active = s == step
            Surface(
                shape = RoundedCornerShape(50),
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text("${index + 1}. $label", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun BusyText(busy: Boolean, text: String) {
    if (busy) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        Spacer(Modifier.width(8.dp))
        Text("Se lucrează…")
    } else {
        Text(text)
    }
}

// ---------------------------------------------------------------------------
// Detaliile unui tracker
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDetailDialog(
    viewModel: DevicesViewModel,
    device: PairedDeviceEntity,
    detail: DeviceDetailUi,
    onCreatePost: (String, Double, Double, Long) -> Unit
) {
    var confirmRevoke by remember { mutableStateOf(false) }
    val revoked = device.status == DeviceStatus.REVOKED

    Dialog(onDismissRequest = viewModel::closeDetail, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(device.name) },
                    navigationIcon = {
                        IconButton(onClick = viewModel::closeDetail) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::loadDetail) { Icon(Icons.Default.Refresh, contentDescription = "Reîncarcă") }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (detail.loading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }

                item {
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(device.id, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                StatusChip(
                                    text = if (revoked) "Revocat" else "Activ",
                                    icon = if (revoked) Icons.Default.Block else Icons.Default.VerifiedUser,
                                    error = revoked
                                )
                            }
                            Text("Model ${device.model} · firmware ${device.firmware} · adresa ${device.host}", style = MaterialTheme.typography.bodySmall)
                            when {
                                revoked -> Text(
                                    "Revocat: serverul respinge orice cerere semnată de acest tracker, iar cheia de asociere a fost ștearsă de pe telefon.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                detail.trackerState != null -> {
                                    val s = detail.trackerState
                                    Text(
                                        "Trackerul răspunde (comandă autentificată cu cheia de asociere)." +
                                                (s.lastReport?.let { " Ultimul loc: ${it.place}." } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                detail.trackerError != null -> Text(
                                    detail.trackerError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // ----- Locurile trimise de tracker -----
                item { SectionTitle("Locuri înregistrate de tracker") }
                detail.visitsError?.let { item { InfoText(it) } }
                if (detail.visits.isEmpty() && detail.visitsError == null && !detail.loading) {
                    item { InfoText("Încă nu a trimis niciun loc. Trackerul raportează periodic, cât timp e pornit.") }
                }
                items(detail.visits.take(20), key = { it.id }) { visit -> VisitRow(visit, onCreatePost) }

                // ----- Actualizari -----
                item { SectionTitle("Actualizări de firmware / configurare") }
                item {
                    InfoText(
                        "Aplicația verifică semnătura producătorului înainte de a trimite o actualizare. " +
                                "Una nesemnată, modificată sau mai veche este refuzată și nu ajunge la tracker."
                    )
                }
                detail.updatesError?.let { item { InfoText(it) } }
                items(detail.updates, key = { it.first.id }) { (manifest, check) ->
                    UpdateRow(
                        manifest = manifest,
                        check = check,
                        enabled = !revoked && detail.installingId == null,
                        installing = detail.installingId == manifest.id,
                        onInstall = { viewModel.install(manifest) }
                    )
                }

                // ----- Revocare -----
                item {
                    Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    if (!revoked) {
                        Button(
                            onClick = { confirmRevoke = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Revocă trackerul (pierdut sau furat)")
                        }
                    } else {
                        OutlinedButton(onClick = viewModel::forget, modifier = Modifier.fillMaxWidth()) {
                            Text("Scoate din listă")
                        }
                    }
                    }
                }
            }
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            icon = { Icon(Icons.Default.Block, contentDescription = null) },
            title = { Text("Revoci trackerul?") },
            text = {
                Text(
                    "Serverul va respinge imediat orice cerere semnată de „${device.name}”, chiar dacă trackerul " +
                            "e în mâinile altcuiva. Cheia de asociere se șterge de pe telefon. Revocarea este definitivă: " +
                            "pentru a-l folosi din nou, trackerul trebuie resetat (cheie nouă) și asociat din nou."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmRevoke = false
                        viewModel.revoke()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Revocă") }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text("Anulează") } }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun InfoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun VisitRow(visit: DeviceVisit, onCreatePost: (String, Double, Double, Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(visit.place.ifBlank { "%.4f, %.4f".format(visit.latitude, visit.longitude) }, style = MaterialTheme.typography.bodyMedium)
            Text(formatPostDate(visit.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { onCreatePost(visit.place, visit.latitude, visit.longitude, parseIsoMillis(visit.at)) }) {
            Text("Postează")
        }
    }
}

@Composable
private fun UpdateRow(
    manifest: UpdateManifest,
    check: UpdateCheck,
    enabled: Boolean,
    installing: Boolean,
    onInstall: () -> Unit
) {
    val valid = check is UpdateCheck.Valid
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                val version = when (check) {
                    is UpdateCheck.Valid -> check.update.version
                    is UpdateCheck.Rejected -> check.previewVersion ?: manifest.id
                }
                Text("Versiunea $version", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusChip(
                    text = if (valid) "Semnătură validă" else "Refuzată",
                    icon = if (valid) Icons.Default.CheckCircle else Icons.Default.GppBad,
                    error = !valid
                )
            }
            when (check) {
                is UpdateCheck.Valid -> {
                    if (check.update.notes.isNotBlank()) Text(check.update.notes, style = MaterialTheme.typography.bodySmall)
                    if (check.update.config.isNotEmpty()) {
                        InfoText(check.update.config.entries.joinToString(" · ") { "${it.key}: ${it.value}" })
                    }
                }
                is UpdateCheck.Rejected -> Text(check.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                // butonul exista si la cele refuzate: la apasare aplicatia arata ca refuza sa le trimita
                OutlinedButton(onClick = onInstall, enabled = enabled) {
                    if (installing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Instalează pe tracker")
                }
            }
        }
    }
}

/** "2026-11-22T20:15:00.000Z" -> milisecunde (0 daca nu se poate citi). */
private fun parseIsoMillis(iso: String): Long = try {
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.parse(iso.take(19))?.time ?: 0L
} catch (e: Exception) {
    0L
}
