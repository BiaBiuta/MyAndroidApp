package com.example.myandroidapp.auth

import android.content.Context
import java.util.UUID

/**
 * Identitatea vizitatorului anonim de pe acest telefon: un id aleator, fara date personale,
 * generat o singura data. Serverul il primeste la /auth/anonymous si incarca mereu acelasi
 * cont de vizitator (vizitator-<id>@anonim.local), si la fel baza de date locala.
 */
class GuestIdentity(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun id(): String =
        prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().take(8).also {
            prefs.edit().putString(KEY_ID, it).apply()
        }

    private companion object {
        const val PREFS_NAME = "guest_identity"
        const val KEY_ID = "guest_id"
    }
}
