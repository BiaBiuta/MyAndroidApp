package com.example.myandroidapp.todo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "posts")
data class Post(
    @PrimaryKey var id: String="",
    var photo: String="",
    var description: String="",
    var user_id: String="",
    var user_profile_photo: String="",
    var user_last_name: String="",
    var user_first_name: String="",
    var professional: Boolean=false,
    var created_at: String="",  // Consistent cu `updated_at`
    var updated_at: String="",
    var isNotSaved: Boolean=false,
    var location: Location=Location() // Se definește separat
)


