package com.example.myandroidapp.core.data.remote

import com.example.myandroidapp.BuildConfig
import com.example.myandroidapp.todo.data.Location
import com.example.myandroidapp.todo.data.LocationDeserializer
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Punctul unic de acces la server (singleton): clientul HTTP, Retrofit si adresa WebSocket.
 * Toate serviciile (AuthService, PostService, UserService) se creeaza din [retrofit],
 * deci toate cererile trec prin [tokenInterceptor] (token-ul JWT se ataseaza automat).
 */
object Api {
    // Adresa vine din build.gradle.kts (SERVER_HOST), ex. "10.0.2.2:3000" pentru emulator
    private val host = BuildConfig.SERVER_HOST
    private val httpUrl = "http://$host/"
    val wsUrl = "ws://$host/"

    private val gson = GsonBuilder()
        .registerTypeAdapter(Location::class.java, LocationDeserializer())
        .create()

    val tokenInterceptor = TokenInterceptor()

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(tokenInterceptor)
        // timp scurt de conectare: daca serverul nu raspunde, aplicatia trece repede pe datele salvate
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS) // pozele (Base64) pot fi mari
        .build()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(httpUrl)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
}
