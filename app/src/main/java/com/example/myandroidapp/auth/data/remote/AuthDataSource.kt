package com.example.myandroidapp.auth.data.remote

import android.util.Log
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.core.TAG
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

class AuthDataSource (){
    interface AuthService{
        @Headers("Content-Type:application/json")
        @POST("/login")
        suspend fun  login(@Body user:User):TokenHolder
    }
    private val authService:AuthService= Api.retrofit.create(AuthService::class.java)
    //functia login de fapt incapsuleaza functia de login din Service intr-un result
    //pentru a vedea daca autentificarea s-a facut cu succes sau nu
    // pentru a gestiona mai usor
    suspend fun login(user: User): Result<TokenHolder> {
        try {
            return Result.success(authService.login(user))
        } catch (e: Exception) {
            Log.w(TAG, "login failed", e)
            return Result.failure(e)
        }
    }
}