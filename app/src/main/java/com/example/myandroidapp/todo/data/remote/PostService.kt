package com.example.myandroidapp.todo.data.remote

import com.example.myandroidapp.todo.data.Post
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * CRUD pe postari, pe server. Token-ul JWT nu mai e un parametru: il ataseaza
 * automat TokenInterceptor (Api.okHttpClient) la fiecare cerere.
 */
interface PostService {
    @GET("/posts")
    suspend fun find(): List<Post>

    @GET("/post/{id}")
    suspend fun read(@Path("id") itemId: String): Post

    @POST("/post")
    suspend fun create(@Body item: Post): Post

    @PUT("/post/{id}")
    suspend fun update(@Path("id") itemId: String, @Body item: Post): Post

    // 204 fara continut
    @DELETE("/post/{id}")
    suspend fun delete(@Path("id") itemId: String): retrofit2.Response<Unit>
}
