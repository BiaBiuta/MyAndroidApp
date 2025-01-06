import android.app.Application
import android.location.Location
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myandroidapp.maps.LocationMonitor
import kotlinx.coroutines.launch

class MyLocationViewModel(application: Application) : AndroidViewModel(application) {
    var uiState by mutableStateOf<Location?>(null)
        private set

    // Locația selectată manual
    var selectedLocation by mutableStateOf<Pair<Double, Double>?>(null)
        private set

    init {
        collectLocation()
    }

    public fun collectLocation() {
        Log.d("MyLocationViewModel", "collectLocation")
        viewModelScope.launch {
            LocationMonitor(getApplication()).currentLocation.collect {
                Log.d("MyLocationViewModel", "collect $it")
                uiState = it
            }
        }
    }

    // Funcție pentru actualizarea locației selectate manual
    fun updateSelectedLocation(latitude: Double, longitude: Double) {
        selectedLocation = Pair(latitude, longitude)
        Log.d("MyLocationViewModel", "Selected location updated: $selectedLocation")
    }

    companion object {
        fun Factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MyLocationViewModel(application)
            }
        }
    }
}
