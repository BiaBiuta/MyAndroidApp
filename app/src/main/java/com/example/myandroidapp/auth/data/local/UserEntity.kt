package com.example.myandroidapp.auth.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Metoda cu care a fost creat contul (Google / Apple sunt simulate local: tema nu cere server). */
object AuthProvider {
    const val EMAIL = "EMAIL"
    const val GOOGLE = "GOOGLE"
    const val APPLE = "APPLE"
    const val ANONYMOUS = "ANONYMOUS"

    fun label(provider: String) = when (provider) {
        GOOGLE -> "Google"
        APPLE -> "Apple"
        ANONYMOUS -> "Anonim"
        else -> "E-mail și parolă"
    }
}

/** Rolurile din aplicatie. */
object UserRole {
    const val USER = "USER"
    const val ADMIN = "ADMIN"
}

/**
 * Un cont care s-a conectat in aplicatie, salvat in baza de date locala (Room),
 * impreuna cu rolul lui si credentialele (hash-ul parolei). Se creeaza / actualizeaza la login.
 *
 * ownerKey = cheia cu care sunt marcate postarile lui (user_id pe Post):
 * id-ul de pe server, sau e-mailul pentru sesiunile locale.
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val email: String,
    val ownerKey: String,
    val displayName: String,
    val role: String = UserRole.USER,
    // Hash-ul parolei (PBKDF2 cu salt, PasswordHasher). Parola in clar nu se salveaza niciodata.
    // Folosit ca login-ul offline sa verifice parola in baza de date locala.
    @ColumnInfo(defaultValue = "")
    val passwordHash: String = "",
    val isBlocked: Boolean = false,
    // metoda de autentificare a contului (AuthProvider); migrarea 8 -> 9 pune EMAIL conturilor vechi
    val provider: String = AuthProvider.EMAIL,
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoginAt: Long = System.currentTimeMillis()
)

val UserEntity.isAdmin: Boolean
    get() = role == UserRole.ADMIN

/** Vizitator anonim: poate doar citi postarile publice (nu publica, nu raporteaza, nu are sectiune privata). */
val UserEntity.isGuest: Boolean
    get() = provider == AuthProvider.ANONYMOUS

/** Utilizator obisnuit cu cont (nu admin, nu vizitator anonim). */
val UserEntity.isRegularUser: Boolean
    get() = !isAdmin && !isGuest
