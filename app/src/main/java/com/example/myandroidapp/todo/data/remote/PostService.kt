package com.example.myandroidapp.todo.data.remote

import com.example.myandroidapp.todo.data.Post
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** Raspunsul la urcarea unei poze: adresa ei pe server ("/photos/<id>"). */
data class PhotoUpload(val id: String = "", val url: String = "", val size: Long = 0)

/** Un comentariu la o postare; can_delete = il poate sterge utilizatorul curent. */
data class Comment(
    val id: String = "",
    val post_id: String = "",
    // "" = comentariu pe primul nivel; altfel id-ul comentariului la care raspunde
    val parent_id: String = "",
    val user_id: String = "",
    val author: String = "",
    val text: String = "",
    val created_at: String = "",
    // "" = needitat; altfel momentul ultimei editari (afisat "editat")
    val edited_at: String = "",
    val like_count: Int = 0,
    val liked_by_me: Boolean = false,
    val can_edit: Boolean = false,
    val can_delete: Boolean = false
)

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
    // versiunea serverului: aplicatia avertizeaza daca ruleaza un server vechi
    // (care ar ignora titlul, rating-ul, ciorna sau locul)
    @GET("/health")
    suspend fun health(): com.google.gson.JsonObject

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

    /** Poza se urca separat, inaintea postarii (corpul = fisierul JPEG). */
    @PUT("/photos/{id}")
    suspend fun uploadPhoto(@Path("id") photoId: String, @Body photo: okhttp3.RequestBody): PhotoUpload

    // ---------- aprecieri si comentarii (doar online) ----------

    @PUT("/post/{id}/like")
    suspend fun like(@Path("id") postId: String): Post

    @DELETE("/post/{id}/like")
    suspend fun unlike(@Path("id") postId: String): Post

    @GET("/post/{id}/comments")
    suspend fun comments(@Path("id") postId: String): List<Comment>

    @POST("/post/{id}/comments")
    suspend fun addComment(@Path("id") postId: String, @Body body: Map<String, String>): Comment

    @DELETE("/comments/{id}")
    suspend fun deleteComment(@Path("id") commentId: String): retrofit2.Response<Unit>

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
