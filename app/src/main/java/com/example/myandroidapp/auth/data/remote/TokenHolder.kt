package com.example.myandroidapp.auth.data.remote

/**
 * Raspunsul serverului la orice login reusit: token-ul JWT, contul, rolul (USER / ADMIN)
 * si metoda de autentificare. role / provider sunt nullable: Gson nu foloseste valorile implicite.
 */
class TokenHolder(
    val token: String,
    val user: User,
    val role: String? = null,
    val provider: String? = null
)
