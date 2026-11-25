package com.example.myandroidapp.core.logging

import android.util.Log
import com.example.myandroidapp.BuildConfig

/**
 * Singurul loc din aplicatie care scrie in logcat (inlocuieste android.util.Log peste tot).
 *
 *  - fiecare mesaj trece prin [LogRedactor]: token-uri, parole, e-mailuri, coordonate si
 *    continutul postarilor sunt ascunse;
 *  - in build-ul RELEASE mesajele de depanare (d / i) nu se mai scriu deloc, iar la erori
 *    (w / e) apare doar tipul exceptiei, nu si mesajul ei (care poate contine date de la server);
 *  - in DEBUG se vede si stack trace-ul, tot curatat.
 */
object SafeLog {
    private const val PREFIX = "WL/"
    private val verbose = BuildConfig.DEBUG

    /**
     * DOAR pentru demonstratie, in build-ul de debug (cardul "Demo securitate" din Profil):
     * oprita, arata ce ar ajunge in logcat fara curatare. In release nu poate fi oprita.
     */
    @Volatile
    var redactionEnabled: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    private fun clean(text: String): String = if (redactionEnabled) LogRedactor.redact(text) else text

    fun d(tag: String, message: String) {
        if (verbose) Log.d(tag(tag), clean(message))
    }

    fun i(tag: String, message: String) {
        if (verbose) Log.i(tag(tag), clean(message))
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag(tag), clean(message) + describe(error))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag(tag), clean(message) + describe(error))
    }

    /** Tag-ul: scurt (max 23 caractere), cu prefixul aplicatiei, si el curatat (un tag poate contine date). */
    private fun tag(raw: String): String = (PREFIX + LogRedactor.redact(raw)).take(23)

    private fun describe(error: Throwable?): String = when {
        error == null -> ""
        verbose -> "\n" + clean(Log.getStackTraceString(error))
        else -> " (${error.javaClass.simpleName})"
    }
}
