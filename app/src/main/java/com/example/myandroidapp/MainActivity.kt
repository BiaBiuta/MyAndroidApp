package com.example.myapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import com.example.myandroidapp.MyAppNavHost
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.sensor.DeviceSensors
import com.example.myandroidapp.ui.theme.MyAppTheme
import com.example.myapp3.ProximitySensor

import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.ilazar.myapp3.util.RequirePermissions
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Log.d(TAG, "onCreate")
            MyApp {
                MyAppNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            (application as MyApplication).container.postRepository.openWsClient()
        }
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch {
            (application as MyApplication).container.postRepository.closeWsClient()
        }
    }
}
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MyApp(content: @Composable () -> Unit) {
    Log.d("MyApp", "recompose")
    MyAppTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            // Adaugă componentele tale aici
            Surface(modifier = Modifier.padding(innerPadding)) {
                content()
            }

            // Exemplu: Adăugare `DeviceSensors` sau alte componente
            //DeviceSensors(modifier = Modifier.padding(innerPadding))
            ProximitySensor(modifier = Modifier.padding(innerPadding))
        }
    }
}


@RequiresApi(Build.VERSION_CODES.O)
@Preview
@Composable
fun PreviewMyApp() {
    MyApp {
        MyAppNavHost()
    }
}
