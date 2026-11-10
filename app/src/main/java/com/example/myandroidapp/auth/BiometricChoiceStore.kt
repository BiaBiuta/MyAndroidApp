package com.example.myandroidapp.auth

import android.content.Context

/**
 * Tine minte, per cont (e-mail), raspunsul la dialogul "Activează autentificarea biometrică".
 *
 * - Dialogul apare DOAR la primul login al unui cont (cand nu exista inca un raspuns).
 * - La login-urile urmatoare (inclusiv fallback-ul cu parola cand amprenta esueaza)
 *   se reaplica alegerea salvata, fara sa mai intrebam.
 * - Alegerea poate fi schimbata oricand din comutatorul de amprenta din ecranul principal.
 *
 * Se pastreaza separat de sesiune, pentru ca Logout sterge sesiunea, dar nu si preferinta.
 */
class BiometricChoiceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** null = utilizatorul nu a fost intrebat inca; altfel alegerea lui. */
    fun choiceFor(email: String): Boolean? {
        val key = keyFor(email)
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun record(email: String, enabled: Boolean) {
        prefs.edit().putBoolean(keyFor(email), enabled).apply()
    }

    private fun keyFor(email: String) = "biometric_" + email.trim().lowercase()

    private companion object {
        const val PREFS_NAME = "biometric_choices"
    }
}
