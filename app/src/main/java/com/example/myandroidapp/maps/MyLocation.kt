package com.example.myandroidapp.maps

import MyLocationViewModel
import android.Manifest
import android.app.Application
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.ilazar.myapp3.util.RequirePermissions


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MyLocation(modifier: Modifier = Modifier,onLocationSelected: (Double, Double) -> Unit) {
    RequirePermissions(
        permissions = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        ),
        modifier = modifier
    ) {
        ShowMyLocation(
            modifier = modifier,
            onLocationSelected = onLocationSelected
        )
    }
}

@Composable
fun ShowMyLocation(modifier: Modifier,onLocationSelected: (Double, Double) -> Unit) {
    val myLocationViewModel = viewModel<MyLocationViewModel>(
        factory = MyLocationViewModel.Factory(
            LocalContext.current.applicationContext as Application
        )
    )

    val location = myLocationViewModel.uiState
    val selectedLocation = myLocationViewModel.selectedLocation

    if (location != null) {
        val latitude = selectedLocation?.first ?: location.latitude
        val longitude = selectedLocation?.second ?: location.longitude

        MyMap(
            lat = latitude,
            long = longitude,
            modifier = modifier,
            onMarkerMoved = onLocationSelected
        )
    } else {
        LinearProgressIndicator()
    }
}
