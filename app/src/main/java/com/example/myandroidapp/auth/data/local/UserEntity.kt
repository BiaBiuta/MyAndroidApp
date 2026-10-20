package com.example.myandroidapp.auth.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

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
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoginAt: Long = System.currentTimeMillis()
)

val UserEntity.isAdmin: Boolean
    get() = role == UserRole.ADMIN
