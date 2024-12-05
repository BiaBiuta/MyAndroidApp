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
    @Query("SELECT * FROM Posts")
    fun getAll(): Flow<List<Post>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: Post)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(items: List<Post>)

    @Update
    suspend fun update(item: Post): Int

    @Query("DELETE FROM Posts")
    suspend fun deleteAll(): Unit

}
