package com.example.myandroidapp.auth.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE email = :email")
    suspend fun findByEmail(email: String): UserEntity?

    @Query("SELECT * FROM users WHERE email = :email")
    fun observeByEmail(email: String): Flow<UserEntity?>

    // Panoul de administrare: toti utilizatorii care s-au conectat pe acest telefon
    @Query("SELECT * FROM users ORDER BY role ASC, email ASC")
    fun observeAll(): Flow<List<UserEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)

    @Query("UPDATE users SET role = :role WHERE email = :email")
    suspend fun setRole(email: String, role: String)

    @Query("UPDATE users SET isBlocked = :blocked WHERE email = :email")
    suspend fun setBlocked(email: String, blocked: Boolean)

    @Query("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND isBlocked = 0")
    suspend fun countActiveAdmins(): Int
}
