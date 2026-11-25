package com.example.myandroidapp.core.data.remote

import com.example.myandroidapp.core.logging.SafeLog
import com.example.myandroidapp.core.TAG
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Interceptor
import okhttp3.Response

/** De ce nu mai e valida sesiunea pe server. */
enum class SessionEnd { EXPIRED, BLOCKED }

/**
 * Ataseaza token-ul JWT ("Authorization: Bearer ...") la FIECARE cerere catre server,
 * intr-un singur loc. Daca serverul respinge sesiunea (401 = token expirat / revocat,
 * 403 cu mesajul de cont blocat), anunta [sessionEnded]; MyAppViewModel deconecteaza utilizatorul.
 */
class TokenInterceptor : Interceptor {

  /** Token-ul sesiunii curente (null = nimeni conectat). Setat la login, sters la logout. */
  @Volatile
  var token: String? = null

  private val _sessionEnded = MutableSharedFlow<SessionEnd>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )
  val sessionEnded: SharedFlow<SessionEnd> = _sessionEnded.asSharedFlow()

  override fun intercept(chain: Interceptor.Chain): Response {
    val original = chain.request()
    val current = token
    // token-urile "local-..." sunt sesiuni offline: serverul nu le cunoaste, nu le trimitem
    if (current.isNullOrEmpty() || isLocalToken(current)) {
      return chain.proceed(original)
    }
    val response = chain.proceed(
      original.newBuilder()
        .header("Authorization", "Bearer $current")
        .build()
    )
    if (token == current) {
      when {
        // 401 la o cerere CU token = sesiunea nu mai e valida pe server
        response.code == 401 -> {
          SafeLog.d(TAG, "401 de la server: sesiunea a expirat")
          _sessionEnded.tryEmit(SessionEnd.EXPIRED)
        }
        // 403 "cont blocat" (peekBody nu consuma raspunsul, il citeste si Retrofit)
        response.code == 403 && response.peekBody(2048).string().contains("blocat") -> {
          SafeLog.d(TAG, "403 de la server: contul a fost blocat")
          _sessionEnded.tryEmit(SessionEnd.BLOCKED)
        }
      }
    }
    return response
  }

  companion object {
    /** Sesiune creata offline (login verificat in baza locala), fara token de la server. */
    fun isLocalToken(token: String) = token.startsWith("local-")
  }
}
