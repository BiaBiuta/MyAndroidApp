package com.example.myandroidapp.todo.data.remote

import com.example.myandroidapp.todo.data.Post
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** Statisticile pentru ecranul de moderare (GET /stats, doar ADMIN). */
data class ServerStats(val users: UserStats, val posts: PostStats) {
    data class UserStats(val total: Int, val active: Int, val blocked: Int, val admins: Int)
    data class PostStats(
        val total: Int,
        val active: Int,
        val drafts: Int,
        val hidden: Int,
        val deletedByModerators: Int
    )
}

/**
 * CRUD pe postari, pe server. Token-ul JWT nu e un parametru: il ataseaza
 * automat TokenInterceptor (Api.okHttpClient) la fiecare cerere.
 */
interface PostService {
    @GET("/posts")
    suspend fun find(): List<Post>

    @GET("/post/{id}")
    suspend fun read(@Path("id") itemId: String): Post

    @POST("/post")
    suspend fun create(@Body item: Post): Post

    // creeaza SAU actualizeaza (upsert): sincronizarea trimite acelasi apel pentru postari
    // create offline si pentru cele editate; 409 = conflict, 410 = stearsa pe server
    @PUT("/post/{id}")
    suspend fun update(@Path("id") itemId: String, @Body item: Post): Post

    // 204 fara continut
    @DELETE("/post/{id}")
    suspend fun delete(@Path("id") itemId: String): retrofit2.Response<Unit>

    // ---- moderare (doar ADMIN) ----

    // body: {"hidden": true | false}
    @PATCH("/post/{id}/visibility")
    suspend fun setVisibility(@Path("id") itemId: String, @Body body: Map<String, Boolean>): Post

    @GET("/stats")
    suspend fun stats(): ServerStats
}
