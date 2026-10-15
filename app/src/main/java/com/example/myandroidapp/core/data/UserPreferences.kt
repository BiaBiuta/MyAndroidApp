package com.example.myandroidapp.core.data

import com.example.myandroidapp.auth.data.remote.User

// biometricEnabled = utilizatorul a ales "Activează" in dialogul de dupa login.
// passwordHash = hash-ul parolei (PasswordHasher), pentru re-autentificarea din sectiunea privata.
// UserPreferences() (toate campurile goale) inseamna "nicio sesiune" -> folosit la Logout.
class UserPreferences(
    val email: String = "",
    val token: String = "",
    val user: User = User(email, "", 0),
    val biometricEnabled: Boolean = false,
    val passwordHash: String = ""
) {
    /**
     * Identificatorul proprietarului postarilor: id-ul de pe server daca exista,
     * altfel e-mail-ul (sesiune locala fara server, unde id-ul e mereu 0).
     */
    val ownerKey: String
        get() = if (user.id != 0) user.id.toString() else email
}
