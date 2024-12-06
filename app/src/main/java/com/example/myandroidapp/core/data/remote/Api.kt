package com.example.myandroidapp.core.data.remote

import com.example.myandroidapp.todo.data.Location
import com.example.myandroidapp.todo.data.LocationDeserializer
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

//e un singleton => exista o singura instanta a sa
//in toata aplicatia
object Api {
    private val url="192.168.1.6:3000"
    private val httpUrl="http://$url/"
    val wsUrl="ws://$url/"
    private var gson=GsonBuilder()
        .registerTypeAdapter(Location::class.java, LocationDeserializer())  // Adaugă adaptorul personalizat
        .create()//pentru serializare si pentru deserializare
    val tokenInterceptor= TokenInterceptor()//creez un token interceptor
    //va fi unul singur pentru ac am o instanta
    //va crea in mod dinamic
    val retrofit = Retrofit.Builder()
        .baseUrl(httpUrl)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    val okHttpClient = OkHttpClient.Builder().apply {
        this.addInterceptor(tokenInterceptor)
//        this.connectTimeout(30, TimeUnit.SECONDS)  // Timeout pentru conexiune
//        this.readTimeout(60, TimeUnit.SECONDS)     // Mărește timeout-ul de citire
//        this.writeTimeout(60, TimeUnit.SECONDS)    // Timeout pentru trimiterea cererii
        this.callTimeout(90, TimeUnit.SECONDS)     // Timeout general pentru apel
    }.build()


}