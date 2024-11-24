package com.example.myandroidapp.core.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.myandroidapp.auth.data.remote.User
import com.example.myandroidapp.core.TAG
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

class UserPreferencesRepository(private val dataStore: DataStore<Preferences>) {
    private object PreferencesKeys {
        val userKey = stringPreferencesKey("user")
        val email = stringPreferencesKey("email")
        val token = stringPreferencesKey("token")
    }

    init {
        Log.d(TAG, "UserPreferencesRepository initialized")
    }

    // Flow pentru a obține datele utilizatorului
    val userPreferencesStream: Flow<UserPreferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())  // Tratează excepțiile de I/O
            } else {
                throw exception
            }
        }
        .map { preferences -> mapUserPreferences(preferences) }  // Mapează datele în UserPreferences

    // Salvează datele utilizatorului
    suspend fun save(userPreferences: UserPreferences) {
        Log.d(TAG, "Saving userPreferences...")
        val userJson = Gson().toJson(userPreferences.user)
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.email] = userPreferences.email
            preferences[PreferencesKeys.token] = userPreferences.token
            preferences[PreferencesKeys.userKey] = userJson
        }
    }

    // Obține obiectul User din DataStore
    suspend fun getUser(): User? {
        val userJson = dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())  // Tratează excepțiile de I/O
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                preferences[PreferencesKeys.userKey]  // Obține șirul JSON
            }
            .first()  // Obține prima valoare

        return if (userJson != null && userJson.isNotEmpty()) {
            Gson().fromJson(userJson, User::class.java)  // Deserializarea JSON în obiect User
        } else {
            null
        }
    }

    // Mapează preferințele salvate într-un obiect UserPreferences
    private fun mapUserPreferences(preferences: Preferences): UserPreferences {
        val userJson = preferences[PreferencesKeys.userKey] ?: ""
        val email = preferences[PreferencesKeys.email] ?: ""
        val token = preferences[PreferencesKeys.token] ?: ""

        val user = if (userJson.isNotEmpty()) {
            Gson().fromJson(userJson, User::class.java)  // Deserializare JSON în User
        } else {
            User("", "", 0)  // Returnează un obiect User gol dacă JSON-ul este gol
        }

        return UserPreferences(
            email = email,
            token = token,
            user = user
        )
    }
}
