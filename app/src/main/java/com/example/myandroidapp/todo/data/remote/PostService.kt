package com.example.myandroidapp.todo.data.remote

import com.example.myandroidapp.todo.data.Post
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface PostService {
    @GET("/posts")
    suspend fun find(@Header("Authorization") authorization: String): List<Post>
    @GET("/post/{id}")
    suspend fun read(
        @Header("Authorization") authorization: String,
        @Path("id") itemId: String?
    ): Post;
    @Headers("Content-Type: application/json")
    @POST("/post")
    suspend fun create(@Header("Authorization") authorization: String, @Body item: Post): Post
    @Headers("Content-Type: application/json")
    @PUT("/post/{id}")
    suspend fun update(
        @Header("Authorization") authorization: String,
        @Path("id") itemId: String?,
        @Body item: Post
    ): Post
}