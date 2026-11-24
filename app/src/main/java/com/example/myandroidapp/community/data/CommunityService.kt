package com.example.myandroidapp.community.data

import com.example.myandroidapp.todo.data.remote.Comment
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.Path

/** O calatorie, asa cum o trimite serverul (si a altor utilizatori). */
data class RemoteTrip(
    val id: String = "",
    val user_id: String = "",
    val title: String = "",
    val description: String = "",
    val start_date: Long = 0,
    val end_date: Long = 0,
    val created_at: String = "",
    val updated_at: String = "",
    val post_count: Int = 0,
    val cover: String = ""
)

data class TripBody(val title: String, val description: String, val start_date: Long, val end_date: Long)

/** Profilul public al unui calator (fara adresa de e-mail). */
data class PublicProfile(
    val id: String = "",
    val name: String = "",
    val provider: String = "",
    val created_at: String = "",
    val post_count: Int = 0,
    val place_count: Int = 0,
    val average_rating: Double = 0.0,
    val likes_received: Int = 0,
    val trip_count: Int = 0,
    val followers: Int = 0,
    val following: Int = 0,
    val followed_by_me: Boolean = false,
    val is_me: Boolean = false
)

interface CommunityService {
    // ---------- calatorii ----------
    @PUT("/trips/{id}")
    suspend fun saveTrip(@Path("id") id: String, @Body body: TripBody): RemoteTrip

    @DELETE("/trips/{id}")
    suspend fun deleteTrip(@Path("id") id: String): Response<Unit>

    @GET("/users/{id}/trips")
    suspend fun trips(@Path("id") userId: String): List<RemoteTrip>

    // ---------- profiluri si urmarire ----------
    @GET("/users/{id}/profile")
    suspend fun profile(@Path("id") userId: String): PublicProfile

    @PUT("/users/{id}/follow")
    suspend fun follow(@Path("id") userId: String): PublicProfile

    @DELETE("/users/{id}/follow")
    suspend fun unfollow(@Path("id") userId: String): PublicProfile

    @GET("/me/following")
    suspend fun following(): List<String>

    // ---------- comentarii: editare, aprecieri ----------
    @PATCH("/comments/{id}")
    suspend fun editComment(@Path("id") id: String, @Body body: Map<String, String>): Comment

    @PUT("/comments/{id}/like")
    suspend fun likeComment(@Path("id") id: String): Comment

    @DELETE("/comments/{id}/like")
    suspend fun unlikeComment(@Path("id") id: String): Comment
}
