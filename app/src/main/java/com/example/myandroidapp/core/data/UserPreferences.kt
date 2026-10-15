package com.example.myandroidapp.core.data

import com.example.myandroidapp.auth.data.remote.User

// biometricEnabled = utilizatorul a ales "Activează" in dialogul de dupa login.
// UserPreferences() (toate campurile goale) inseamna "nicio sesiune" -> folosit la Logout.
class UserPreferences(
    val email: String = "",
    val token: String = "",
    val user: User = User(email, "", 0),
    val biometricEnabled: Boolean = false
)
