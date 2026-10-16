package com.example.myapp.todo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.myandroidapp.todo.data.Post

import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    // Lista principala: DOAR postarile publice. Postarile private nu apar aici niciodata.
    @Query("SELECT * FROM Posts WHERE isPrivate = 0 ORDER BY created_at DESC")
    fun getAll(): Flow<List<Post>>

    // Sectiunea privata: doar postarile private ale proprietarului dat.
    @Query("SELECT * FROM Posts WHERE isPrivate = 1 AND user_id = :ownerKey ORDER BY created_at DESC")
    fun getPrivate(ownerKey: String): Flow<List<Post>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: Post)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(items: List<Post>)

    @Update
    suspend fun update(item: Post): Int

    // Sterge doar postarile publice (cele venite de pe server). Cele private raman,
    // ca utilizatorul sa le regaseasca dupa ce se logheaza din nou.
    @Query("DELETE FROM Posts WHERE isPrivate = 0")
    suspend fun deleteAllPublic(): Unit

    // La refresh: inlocuim postarile publice venite de pe server, dar NU pe cele
    // create pe telefon si inca netrimise (isNotSaved = 1), ca sa nu se piarda.
    @Query("DELETE FROM Posts WHERE isPrivate = 0 AND isNotSaved = 0")
    suspend fun deleteAllPublicSynced(): Unit

    @Query("DELETE FROM Posts WHERE id = :id")
    suspend fun deleteById(id: String): Unit

    // Postarile private nu se trimit niciodata pe server
    @Query("SELECT * FROM Posts WHERE isNotSaved = 1 AND isPrivate = 0")
    suspend fun getUnsyncedItems(): List<Post>

    @Query("UPDATE Posts SET isNotSaved = 0 WHERE id = :id")
    suspend fun markAsSynced(id: String)
}
