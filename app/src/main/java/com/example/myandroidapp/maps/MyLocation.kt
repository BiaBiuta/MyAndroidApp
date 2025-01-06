package com.example.myandroidapp.maps

import MyLocationViewModel
import android.Manifest
import android.app.Application
import android.util.Log
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.ilazar.myapp3.util.RequirePermissions

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MyLocation(modifier: Modifier = Modifier, onLocationSelected: (Double, Double) -> Unit) {
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    )

    Log.d("Permissions", "Permissions granted: ${permissionsState.allPermissionsGranted}")

    if (permissionsState.allPermissionsGranted) {
        Log.d("Permissions", "Permissions granted, loading map.")
        // Permisiunile sunt acordate, încarcă harta
        ShowMyLocation(
            modifier = modifier,
            onLocationSelected = onLocationSelected
        )
    } else {
        // Afișează mesajul pentru a cere permisiuni
        RequirePermissions(
            permissions = listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            ),
            modifier = modifier
        ) {
//            Log.d("Permissions", "Permissions granted after request, loading map.")
//            val myLocationViewModel = viewModel<MyLocationViewModel>(
//                factory = MyLocationViewModel.Factory(
//                    LocalContext.current.applicationContext as Application
//                )
//            )
//            val selectedLocation = myLocationViewModel.collectLocation()
            // Permisiunile au fost acordate, încarcă harta
            ShowMyLocation(
                modifier = modifier,
                onLocationSelected =  onLocationSelected
            )
        }
    }
}

@Composable
fun ShowMyLocation(modifier: Modifier, onLocationSelected: (Double, Double) -> Unit) {
    val myLocationViewModel = viewModel<MyLocationViewModel>(
        factory = MyLocationViewModel.Factory(
            LocalContext.current.applicationContext as Application
        )
    )

    myLocationViewModel.collectLocation()
    val location = myLocationViewModel.uiState
    // Locația curentă obținută prin ViewModel
    //val selectedLocation = myLocationViewModel.selectedLocation // Locația selectată manual

    Log.d("Location", "Location: $location")

    if (location != null) {
        val latitude =  location.latitude
        val longitude = location.longitude

        Log.d("Location", "Latitude: $latitude, Longitude: $longitude")

        MyMap(
            lat = latitude,
            long = longitude,
            modifier = modifier,
            onMarkerMoved = onLocationSelected
        )
    } else {
        LinearProgressIndicator() // Afișează un indicator de progres în cazul în care locația nu a fost încă obținută
    }
}
