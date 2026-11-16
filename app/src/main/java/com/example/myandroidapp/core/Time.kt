package com.example.myandroidapp.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Momentul curent in format ISO 8601 UTC ("2026-11-16T18:30:00.000Z"), identic cu
 * toISOString() de pe server, deci datele se pot compara si ca text.
 * (java.time.Instant ar cere Android 8; aplicatia merge de la Android 7.)
 */
fun isoNow(): String = isoFormat().format(Date())

private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}
