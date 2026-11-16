package com.ilazar.myapp3.util

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@ExperimentalPermissionsApi
@Composable
fun RequirePermissions(
    permissions: List<String>,
    modifier: Modifier,
    content: @Composable () -> Unit = { }
) {
    val permissionsState = rememberMultiplePermissionsState(permissions = permissions)
    if (permissionsState.allPermissionsGranted) {
        content()
    } else {
        Column(modifier = modifier) {
            Text(
                getTextToShowGivenPermissions(
                    permissionsState.revokedPermissions,
                    permissionsState.shouldShowRationale
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                Text("Permite accesul")
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
private fun getTextToShowGivenPermissions(
    permissionStates: List<PermissionState>,
    shouldShowRationale: Boolean
): String {
    if (permissionStates.isEmpty()) return ""
    val names = permissionStates.map { friendlyName(it.permission) }.distinct().joinToString(", ")
    return if (shouldShowRationale) {
        "Aplicația are nevoie de acces la: $names. Te rugăm să-l permiți ca funcția să meargă."
    } else {
        "Accesul la $names nu a fost acordat. Îl poți permite acum sau din Setări → Aplicații."
    }
}

private fun friendlyName(permission: String): String = when {
    permission.contains("LOCATION") -> "locație"
    permission.contains("CAMERA") -> "cameră"
    permission.contains("NOTIFICATIONS") -> "notificări"
    permission.contains("IMAGES") || permission.contains("STORAGE") -> "poze"
    else -> permission.substringAfterLast('.')
}
