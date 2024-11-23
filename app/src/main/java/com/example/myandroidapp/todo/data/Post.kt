package com.example.myandroidapp.todo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

//@Entity(tableName = "posts")
data class Post(
    val id: String="",
    val photo: String="",
    val description: String="",
    val user_id: String="",
    val user_profile_photo: String="",
    val user_last_name: String="",
    val user_first_name: String="",
    val professional: Boolean=false,
    val created_at: String="",  // Consistent cu `updated_at`
    val updated_at: String="",
    val isNotSaved: Boolean=false,
    val location: Location=Location() // Se definește separat
)


