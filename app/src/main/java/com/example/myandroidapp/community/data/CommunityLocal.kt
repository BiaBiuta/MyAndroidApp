package com.example.myandroidapp.community.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * O calatorie (album) a utilizatorului ownerKey. Ca postarile: se poate crea / edita / sterge
 * offline (syncStatus PENDING) si se trimite la server la sincronizare, INAINTEA postarilor.
 * Calatoriile altor utilizatori nu se pastreaza aici (se citesc de pe server, in profilul lor).
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val ownerKey: String,
    val title: String,
    val description: String = "",
    val startDate: Long = 0,
    val endDate: Long = 0,
    val updatedAt: String = "",
    val syncStatus: String = "PENDING",
    val syncError: String = "",
    val isDeleted: Boolean = false
)

@Dao
interface TripDao {
    @Query("SELECT * FROM trips WHERE ownerKey = :ownerKey AND isDeleted = 0 ORDER BY startDate DESC, title")
    fun observe(ownerKey: String): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun find(id: String): TripEntity?

    @Query("SELECT * FROM trips WHERE ownerKey = :ownerKey AND syncStatus = 'PENDING'")
    suspend fun pending(ownerKey: String): List<TripEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(trip: TripEntity)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE trips SET syncStatus = 'FAILED', syncError = :message WHERE id = :id")
    suspend fun markFailed(id: String, message: String)
}

/** Pe cine urmareste contul ownerKey (copie locala a GET /me/following: filtrul merge si offline). */
@Entity(tableName = "following", primaryKeys = ["ownerKey", "userId"])
data class FollowEntity(val ownerKey: String, val userId: String)

@Dao
interface FollowDao {
    @Query("SELECT userId FROM following WHERE ownerKey = :ownerKey")
    fun observe(ownerKey: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(follow: FollowEntity)

    @Query("DELETE FROM following WHERE ownerKey = :ownerKey AND userId = :userId")
    suspend fun remove(ownerKey: String, userId: String)

    @Query("DELETE FROM following WHERE ownerKey = :ownerKey")
    suspend fun clear(ownerKey: String)
}

/** Postarile salvate ("idei de calatorie") ale contului ownerKey; doar pe telefon, merg si offline. */
@Entity(tableName = "saved_posts", primaryKeys = ["ownerKey", "postId"])
data class SavedPostEntity(val ownerKey: String, val postId: String, val savedAt: Long)

@Dao
interface SavedPostDao {
    @Query("SELECT postId FROM saved_posts WHERE ownerKey = :ownerKey ORDER BY savedAt DESC")
    fun observe(ownerKey: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(saved: SavedPostEntity)

    @Query("DELETE FROM saved_posts WHERE ownerKey = :ownerKey AND postId = :postId")
    suspend fun remove(ownerKey: String, postId: String)
}
