package com.example.myandroidapp.core.data

import com.example.myandroidapp.auth.data.remote.User

class UserPreferences(val email: String="", val token: String="",val user: User =User(email,"",0)) {
}