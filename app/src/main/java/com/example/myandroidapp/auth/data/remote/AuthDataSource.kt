package com.example.myandroidapp.auth.data.remote

import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.core.data.remote.apiCall
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Sursa de date de la distanta pentru autentificare (endpoint-urile serverului).
 * Erorile ies ca ServerException (serverul a refuzat) sau OfflineException (server indisponibil),
 * iar AuthRepository decide ce face cu ele.
 */
class AuthDataSource {

    data class Credentials(val email: String, val password: String)
    data class ProviderRequest(val provider: String, val email: String)
    data class AnonymousRequest(val guestId: String)

    interface AuthService {
        @POST("/login")
        suspend fun login(@Body credentials: Credentials): TokenHolder

        @POST("/signup")
        suspend fun signup(@Body credentials: Credentials): TokenHolder

        // Google / Apple (simulat pe server)
        @POST("/auth/provider")
        suspend fun provider(@Body request: ProviderRequest): TokenHolder

        @POST("/auth/anonymous")
        suspend fun anonymous(@Body request: AnonymousRequest): TokenHolder

        // revoca token-ul curent pe server (204)
        @POST("/logout")
        suspend fun logout(): retrofit2.Response<Unit>
    }

    private val authService: AuthService = Api.retrofit.create(AuthService::class.java)

    suspend fun login(email: String, password: String): TokenHolder =
        apiCall { authService.login(Credentials(email, password)) }

    suspend fun signup(email: String, password: String): TokenHolder =
        apiCall { authService.signup(Credentials(email, password)) }

    suspend fun signInWithProvider(provider: String, email: String): TokenHolder =
        apiCall { authService.provider(ProviderRequest(provider, email)) }

    suspend fun signInAnonymously(guestId: String): TokenHolder =
        apiCall { authService.anonymous(AnonymousRequest(guestId)) }

    suspend fun logout() {
        apiCall { authService.logout() }
    }
}
