//package com.ilazar.mymap.ui
//
//import android.app.Application
//import androidx.compose.material.LinearProgressIndicator
//import androidx.compose.material.MaterialTheme
//import androidx.compose.material.Text
//import androidx.compose.runtime.Composable
//import androidx.compose.ui.platform.LocalContext
//import androidx.lifecycle.viewmodel.compose.viewModel
//import com.example.myapp.MyApp
//
//@Composable
//fun MyLocation() {
//    val myNewtworkStatusViewModel = viewModel<MyLocationViewModel>(
//        factory = MyLocationViewModel.Factory(
//            LocalContext.current.applicationContext as Application
//        )
//    )
//    val location = myNewtworkStatusViewModel.uiState
//    if (location != null) {
//        MyApp(location.latitude, location.longitude)
//    } else {
//        LinearProgressIndicator()
//    }
//}
