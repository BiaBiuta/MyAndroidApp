package com.example.myandroidapp.maps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Un loc gasit: numele scurt (ex. "Cheile Turzii, Cluj") si coordonatele. */
data class Place(val name: String, val latitude: Double, val longitude: Double)

/**
 * Cautarea locurilor dupa nume si numele unui punct de pe harta, fara servicii platite:
 *  1. Geocoder-ul din Android (gratuit, inclus in sistem);
 *  2. daca telefonul nu il are sau nu gaseste nimic: Nominatim (cautarea OpenStreetMap, gratuita).
 * Ruleaza pe un fir de fundal; fara internet intoarce o lista goala / null.
 */
object PlaceSearch {
    private const val TAG = "PlaceSearch"
    private val locale = Locale("ro", "RO")

    // client separat de Api.okHttpClient: token-ul aplicatiei NU trebuie trimis la servere externe
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun search(context: Context, query: String): List<Place> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.length < 2) return@withContext emptyList()
        geocoderSearch(context, text).ifEmpty { nominatimSearch(context, text) }
    }

    /** Numele locului de la coordonatele date (ex. dupa GPS sau o atingere pe harta). */
    suspend fun nameOf(context: Context, latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        geocoderName(context, latitude, longitude) ?: nominatimName(context, latitude, longitude)
    }

    // ---------- Geocoder (Android) ----------

    @Suppress("DEPRECATION")
    private fun geocoderSearch(context: Context, query: String): List<Place> = try {
        if (!Geocoder.isPresent()) emptyList()
        else Geocoder(context, locale).getFromLocationName(query, 5).orEmpty()
            .filter { it.hasLatitude() && it.hasLongitude() }
            .map { Place(shortName(it), it.latitude, it.longitude) }
    } catch (e: Exception) {
        Log.w(TAG, "geocoder search failed", e)
        emptyList()
    }

    @Suppress("DEPRECATION")
    private fun geocoderName(context: Context, latitude: Double, longitude: Double): String? = try {
        if (!Geocoder.isPresent()) null
        else Geocoder(context, locale).getFromLocation(latitude, longitude, 1)?.firstOrNull()?.let(::shortName)
    } catch (e: Exception) {
        Log.w(TAG, "geocoder reverse failed", e)
        null
    }

    private fun shortName(address: Address): String {
        val parts = listOfNotNull(
            address.featureName?.takeIf { name -> name.any { it.isLetter() } },
            address.locality ?: address.subAdminArea,
            address.adminArea,
            address.countryName.takeIf { address.countryCode != "RO" }
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return parts.take(3).joinToString(", ").ifEmpty { address.getAddressLine(0).orEmpty() }
    }

    // ---------- Nominatim (OpenStreetMap) ----------

    private fun nominatimSearch(context: Context, query: String): List<Place> = try {
        val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "5")
            .addQueryParameter("accept-language", "ro")
            .build()
        val body = get(context, url.toString()) ?: return emptyList()
        JsonParser().parse(body).asJsonArray.mapNotNull { element ->
            val o = element.asJsonObject
            val lat = o.get("lat")?.asString?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = o.get("lon")?.asString?.toDoubleOrNull() ?: return@mapNotNull null
            Place(shorten(o.get("display_name")?.asString.orEmpty()), lat, lon)
        }
    } catch (e: Exception) {
        Log.w(TAG, "nominatim search failed", e)
        emptyList()
    }

    private fun nominatimName(context: Context, latitude: Double, longitude: Double): String? = try {
        val url = "https://nominatim.openstreetmap.org/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", latitude.toString())
            .addQueryParameter("lon", longitude.toString())
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("zoom", "14")
            .addQueryParameter("accept-language", "ro")
            .build()
        get(context, url.toString())?.let { body ->
            JsonParser().parse(body).asJsonObject.get("display_name")?.asString?.let(::shorten)
        }
    } catch (e: Exception) {
        Log.w(TAG, "nominatim reverse failed", e)
        null
    }

    private fun get(context: Context, url: String): String? {
        val request = Request.Builder()
            .url(url)
            // politica Nominatim: aplicatia se identifica
            .header("User-Agent", "WanderLog/1.0 (${context.packageName})")
            .build()
        http.newCall(request).execute().use { response ->
            return if (response.isSuccessful) response.body?.string() else null
        }
    }

    /** "Cheile Turzii, Petreștii de Jos, Cluj, 407450, România" -> "Cheile Turzii, Petreștii de Jos, Cluj". */
    private fun shorten(displayName: String): String =
        displayName.split(",").map { it.trim() }
            .filter { part -> part.isNotEmpty() && part.none { it.isDigit() } }
            .take(3)
            .joinToString(", ")
}

/**
 * Locatia curenta a telefonului (o singura data). Necesita permisiunea de locatie,
 * ceruta inainte de ecranul care apeleaza functia. null = indisponibila (GPS oprit, timeout).
 */
@SuppressLint("MissingPermission")
suspend fun currentDeviceLocation(context: Context): android.location.Location? = withTimeoutOrNull(15_000) {
    suspendCancellableCoroutine { continuation ->
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cancellation = CancellationTokenSource()
        fun finish(location: android.location.Location?) {
            if (continuation.isActive) continuation.resume(location)
        }
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
            .addOnSuccessListener { location ->
                if (location != null) finish(location)
                else client.lastLocation
                    .addOnSuccessListener { finish(it) }
                    .addOnFailureListener { finish(null) }
            }
            .addOnFailureListener { finish(null) }
        continuation.invokeOnCancellation { cancellation.cancel() }
    }
}
