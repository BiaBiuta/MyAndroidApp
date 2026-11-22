package com.example.myandroidapp.iot.ui

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
import com.example.myandroidapp.auth.data.local.isRegularUser
import com.example.myandroidapp.iot.data.DeviceRepository
import com.example.myandroidapp.iot.data.PairingException
import com.example.myandroidapp.iot.data.PendingPairing
import com.example.myandroidapp.iot.data.TrackerInfo
import com.example.myandroidapp.iot.data.TrackerState
import com.example.myandroidapp.iot.data.deviceMessage
import com.example.myandroidapp.iot.data.local.PairedDeviceEntity
import com.example.myandroidapp.iot.data.remote.DeviceVisit
import com.example.myandroidapp.iot.update.UpdateCheck
import com.example.myandroidapp.iot.update.UpdateManifest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class PairStep { ADDRESS, CODE }

/** Asistentul de asociere: adresa trackerului -> codul de pe ecranul lui. */
data class PairingUi(
    val step: PairStep = PairStep.ADDRESS,
    val host: String = DeviceRepository.DEFAULT_HOST,
    val name: String = "",
    val info: TrackerInfo? = null,
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null
)

/** Detaliile unui tracker: starea lui (direct de la tracker), locurile (server), actualizarile. */
data class DeviceDetailUi(
    val deviceId: String,
    val trackerState: TrackerState? = null,
    val trackerError: String? = null,
    val visits: List<DeviceVisit> = emptyList(),
    val visitsError: String? = null,
    val updates: List<Pair<UpdateManifest, UpdateCheck>> = emptyList(),
    val updatesError: String? = null,
    val loading: Boolean = false,
    val installingId: String? = null
)

class DevicesViewModel(
    private val repository: DeviceRepository,
    private val roleRepository: RoleRepository
) : ViewModel() {

    val devices: StateFlow<List<PairedDeviceEntity>> =
        repository.devices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var message by mutableStateOf<String?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var pairing by mutableStateOf<PairingUi?>(null)
        private set
    var detail by mutableStateOf<DeviceDetailUi?>(null)
        private set

    private var pending: PendingPairing? = null

    init {
        refresh()
    }

    fun consumeMessage() {
        message = null
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing = true
            repository.refreshFromServer()?.let { message = "Starea de pe server nu a putut fi citită: $it" }
            refreshing = false
        }
    }

    // ---------- Asociere ----------

    fun openPairing() {
        pending = null
        pairing = PairingUi()
    }

    fun closePairing() {
        pending = null
        pairing = null
    }

    fun onHostChange(value: String) {
        pairing = pairing?.copy(host = value.trim(), error = null, info = null)
    }

    fun onNameChange(value: String) {
        if (value.length <= 60) pairing = pairing?.copy(name = value)
    }

    fun onCodeChange(value: String) {
        val digits = value.filter(Char::isDigit).take(6)
        pairing = pairing?.copy(code = digits, error = null)
    }

    /** Pasii 1-2: cheile si nonce-urile; trackerul afiseaza apoi codul. */
    fun startPairing() {
        val ui = pairing ?: return
        viewModelScope.launch {
            val user = roleRepository.currentUserOnce()
            if (user == null || !user.isRegularUser) {
                pairing = ui.copy(error = "Doar utilizatorii cu cont pot asocia trackere.")
                return@launch
            }
            pairing = ui.copy(busy = true, error = null)
            pairing = try {
                val started = repository.beginPairing(ui.host)
                pending = started
                ui.copy(step = PairStep.CODE, info = started.info, busy = false, code = "")
            } catch (e: Exception) {
                ui.copy(busy = false, error = e.deviceMessage("Asocierea nu a putut începe."))
            }
        }
    }

    /** Pasul 3: codul tastat de utilizator. */
    fun confirmCode() {
        val ui = pairing ?: return
        val started = pending ?: return
        viewModelScope.launch {
            pairing = ui.copy(busy = true, error = null)
            try {
                val device = repository.completePairing(started, ui.code, ui.name)
                pending = null
                pairing = null
                message = "Trackerul „${device.name}” a fost asociat în siguranță și înregistrat pe server."
            } catch (e: PairingException) {
                if (e.fatal) pending = null
                pairing = if (e.fatal) ui.copy(step = PairStep.ADDRESS, busy = false, code = "", error = e.message)
                else ui.copy(busy = false, code = "", error = e.message)
            } catch (e: Exception) {
                pending = null
                pairing = ui.copy(step = PairStep.ADDRESS, busy = false, code = "", error = e.deviceMessage("Asocierea nu a reușit."))
            }
        }
    }

    // ---------- Detalii ----------

    fun open(device: PairedDeviceEntity) {
        detail = DeviceDetailUi(deviceId = device.id)
        loadDetail()
    }

    fun closeDetail() {
        detail = null
    }

    fun selectedDevice(): PairedDeviceEntity? = detail?.let { d -> devices.value.find { it.id == d.deviceId } }

    fun loadDetail() {
        val device = selectedDevice() ?: return
        viewModelScope.launch {
            detail = detail?.copy(loading = true)
            val state = if (device.status == "ACTIVE") runCatching { repository.trackerState(device) } else null
            val visits = runCatching { repository.visits(device) }
            val updates = runCatching { repository.updates(device) }
            detail = detail?.copy(
                loading = false,
                trackerState = state?.getOrNull(),
                trackerError = state?.exceptionOrNull()?.deviceMessage("Trackerul nu răspunde."),
                visits = visits.getOrDefault(emptyList()),
                visitsError = visits.exceptionOrNull()?.deviceMessage("Locurile nu au putut fi încărcate."),
                updates = updates.getOrDefault(emptyList()),
                updatesError = updates.exceptionOrNull()?.deviceMessage("Actualizările nu au putut fi încărcate.")
            )
        }
    }

    fun install(manifest: UpdateManifest) {
        val device = selectedDevice() ?: return
        viewModelScope.launch {
            detail = detail?.copy(installingId = manifest.id)
            message = try {
                repository.install(device, manifest)
            } catch (e: Exception) {
                e.deviceMessage("Actualizarea nu a putut fi instalată.")
            }
            detail = detail?.copy(installingId = null)
            loadDetail()
        }
    }

    fun revoke() {
        val device = selectedDevice() ?: return
        viewModelScope.launch {
            message = try {
                repository.revoke(device)
                "Trackerul a fost revocat: serverul nu mai acceptă nimic de la el, iar cheia de pe telefon a fost ștearsă."
            } catch (e: Exception) {
                e.deviceMessage("Revocarea nu a reușit. Este nevoie de conexiune la server.")
            }
            loadDetail()
        }
    }

    fun forget() {
        val device = selectedDevice() ?: return
        viewModelScope.launch {
            message = try {
                repository.forget(device)
                detail = null
                "Trackerul revocat a fost scos din listă."
            } catch (e: Exception) {
                e.deviceMessage("Trackerul nu a putut fi scos din listă.")
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication
                DevicesViewModel(app.container.deviceRepository, app.container.roleRepository)
            }
        }
    }
}
