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
    @Query("SELECT * FROM Posts ORDER BY created_at DESC")
    fun getAll(): Flow<List<Post>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: Post)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(items: List<Post>)

    @Update
    suspend fun update(item: Post): Int

    @Query("DELETE FROM Posts")
    suspend fun deleteAll(): Unit
    @Query("SELECT * FROM Posts WHERE isNotSaved = 1")
    suspend fun getUnsyncedItems(): List<Post>

    @Query("UPDATE Posts SET isNotSaved = 0 WHERE id = :id")
    suspend fun markAsSynced(id: String)
}
