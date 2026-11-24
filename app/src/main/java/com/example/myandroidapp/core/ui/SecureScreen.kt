package com.example.myandroidapp.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Protectia ecranelor cu date sensibile (seiful privat): cat timp ecranul e afisat, fereastra are
 * FLAG_SECURE, deci:
 *  - capturile de ecran si inregistrarile ecranului ies negre / sunt blocate de sistem;
 *  - in lista aplicatiilor recente (Recents) previzualizarea aplicatiei e goala;
 *  - continutul nu apare pe un ecran extern / la proiectare (Cast) nesecurizat.
 *
 * Mai multe ecrane pot cere protectia in acelasi timp (ex. seiful si formularul de postare privata,
 * in timpul tranzitiei dintre ele): numaram cererile si scoatem flag-ul doar cand nu mai e niciuna.
 */
@Composable
fun SecureScreen() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        SecureWindow.acquire(activity)
        onDispose { SecureWindow.release(activity) }
    }
}

private object SecureWindow {
    private var holders = 0

    fun acquire(activity: Activity) {
        holders++
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(activity: Activity) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
