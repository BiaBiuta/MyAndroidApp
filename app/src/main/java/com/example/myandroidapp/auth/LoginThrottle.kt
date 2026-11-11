package com.example.myandroidapp.auth

import android.content.Context

/**
 * Protectie impotriva ghicirii parolei (brute force): dupa [MAX_ATTEMPTS] incercari gresite
 * pentru acelasi cont, login-ul se blocheaza temporar. Fiecare blocare noua dureaza dublu
 * (30 s, 1 min, 2 min ... maxim 15 min). Un login reusit sterge istoricul.
 *
 * Starea se salveaza pe disc, deci repornirea aplicatiei NU ocoleste blocarea.
 */
class LoginThrottle(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Cate milisecunde mai dureaza blocarea pentru acest cont (0 = nu e blocat). */
    fun remainingLockMs(email: String): Long {
        val until = prefs.getLong(key(email, "until"), 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Cate incercari mai are contul pana la urmatoarea blocare. */
    fun attemptsLeft(email: String): Int =
        MAX_ATTEMPTS - prefs.getInt(key(email, "failures"), 0)

    /**
     * Inregistreaza o incercare gresita. Intoarce durata blocarii in ms daca acum
     * contul a fost blocat, altfel 0.
     */
    fun registerFailure(email: String): Long {
        val failures = prefs.getInt(key(email, "failures"), 0) + 1
        if (failures < MAX_ATTEMPTS) {
            prefs.edit().putInt(key(email, "failures"), failures).apply()
            return 0L
        }
        val lockouts = prefs.getInt(key(email, "lockouts"), 0) + 1
        val lockMs = (BASE_LOCK_MS shl (lockouts - 1).coerceAtMost(5)).coerceAtMost(MAX_LOCK_MS)
        prefs.edit()
            .putInt(key(email, "failures"), 0)
            .putInt(key(email, "lockouts"), lockouts)
            .putLong(key(email, "until"), System.currentTimeMillis() + lockMs)
            .apply()
        return lockMs
    }

    fun registerSuccess(email: String) {
        prefs.edit()
            .remove(key(email, "failures"))
            .remove(key(email, "lockouts"))
            .remove(key(email, "until"))
            .apply()
    }

    private fun key(email: String, field: String) = field + "_" + email.trim().lowercase()

    companion object {
        const val MAX_ATTEMPTS = 5
        private const val BASE_LOCK_MS = 30_000L
        private const val MAX_LOCK_MS = 15 * 60_000L
        private const val PREFS_NAME = "login_throttle"
    }
}
