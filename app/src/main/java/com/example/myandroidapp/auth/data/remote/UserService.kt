package com.example.myandroidapp.auth.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path

/** Contul unui utilizator, asa cum il trimite serverul (fara hash-ul parolei). */
data class RemoteUser(
    val id: Int,
    val email: String,
    val role: String?,
    val provider: String?,
    val blocked: Boolean
)

/** Administrarea utilizatorilor pe server (doar ADMIN; serverul verifica rolul din token). */
interface UserService {
    @GET("/users")
    suspend fun list(): List<RemoteUser>

    // body: {"role": "USER" | "ADMIN"} sau {"blocked": true | false}
    @PATCH("/users/{id}")
    suspend fun update(@Path("id") id: Int, @Body changes: Map<String, @JvmSuppressWildcards Any>): RemoteUser
}
