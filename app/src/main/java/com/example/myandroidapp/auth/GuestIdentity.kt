package com.example.myandroidapp.auth

import android.content.Context
import java.util.UUID

/**
 * Identitatea vizitatorului anonim de pe acest telefon. Se genereaza o singura data
 * (id aleator, fara date personale), deci la fiecare "Explorează ca vizitator anonim"
 * se incarca acelasi cont local de vizitator din baza de date.
 */
class GuestIdentity(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Adresa interna a vizitatorului, de forma "vizitator-1a2b3c4d@anonim.local". */
    fun email(): String {
        val id = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().take(8).also {
            prefs.edit().putString(KEY_ID, it).apply()
        }
        return "vizitator-$id@$GUEST_DOMAIN"
    }

    companion object {
        const val GUEST_DOMAIN = "anonim.local"
        private const val PREFS_NAME = "guest_identity"
        private const val KEY_ID = "guest_id"
    }
}
