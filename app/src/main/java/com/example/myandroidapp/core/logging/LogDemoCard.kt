package com.example.myandroidapp.core.logging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myandroidapp.BuildConfig
import com.example.myandroidapp.core.data.remote.Api

private const val DEMO_TAG = "Demo"

/**
 * Card pentru demonstratia "Secure logging" (bonus App Hardening), in ecranul Profil.
 * Exista DOAR in build-ul de debug: in release nu se afiseaza, iar SafeLog nu poate fi oprit.
 *
 *  1. comutatorul oprit + "Scrie în log" -> in logcat apar e-mailul, tokenul, coordonatele (ATACUL);
 *  2. comutatorul pornit + "Scrie în log" -> aceleasi mesaje apar curatate (APARAREA).
 */
@Composable
fun LogDemoCard(email: String) {
    if (!BuildConfig.DEBUG) return
    var redact by remember { mutableStateOf(SafeLog.redactionEnabled) }
    var written by remember { mutableIntStateOf(0) }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Demo securitate: loguri", style = MaterialTheme.typography.titleMedium)
            Text(
                "Doar în build-ul de debug. Pe calculator deschide Logcat cu filtrul „tag:WL/Demo”, apoi apasă butonul.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ascunde datele sensibile", modifier = Modifier.weight(1f))
                Switch(
                    checked = redact,
                    onCheckedChange = {
                        redact = it
                        SafeLog.redactionEnabled = it
                    }
                )
            }
            Button(
                onClick = {
                    writeDemoLogs(email)
                    written++
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Scrie în log date de test") }
            if (written > 0) {
                Text(
                    if (redact) "Scris în log, cu datele ascunse." else "Scris în log FĂRĂ curățare: așa ar arăta un log nesigur.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (redact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** Mesaje tipice pe care o aplicatie le scrie in log: login, cerere HTTP, GPS, postare, parola. */
private fun writeDemoLogs(email: String) {
    val token = Api.tokenInterceptor.token ?: "(nu exista token: sesiune offline)"
    SafeLog.d(DEMO_TAG, "----- demo, curatare ${if (SafeLog.redactionEnabled) "PORNITA" else "OPRITA"} -----")
    SafeLog.d(DEMO_TAG, "login reusit: $email")
    SafeLog.d(DEMO_TAG, "GET /posts  Authorization: Bearer $token")
    SafeLog.d(DEMO_TAG, "locatie GPS: Location[fused 46.770439,23.591423 hAcc=12.0]")
    SafeLog.d(
        DEMO_TAG,
        """postare primita: {"id":"p1","title":"Cheile Turzii","description":"Am urcat cu Ana","location":{"latitude":46.5642,"longitude":23.6814}}"""
    )
    SafeLog.d(DEMO_TAG, """schimbare parola: {"currentPassword":"Veche#1234","newPassword":"Noua#56789"}""")
}
