package com.example.myandroidapp.maps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.example.myandroidapp.core.logging.SafeLog
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
 *  2. Photon (cautare gratuita pe datele OpenStreetMap, komoot);
 *  3. Nominatim (cautarea oficiala OpenStreetMap).
 * Se trece la urmatorul doar daca precedentul nu gaseste nimic sau nu raspunde.
 * Ruleaza pe un fir de fundal; [lastProblem] spune de ce nu s-a gasit nimic (ex. fara internet).
 */
object PlaceSearch {
    private const val TAG = "PlaceSearch"
    private val locale = Locale("ro", "RO")

    // client separat de Api.okHttpClient: token-ul aplicatiei NU trebuie trimis la servere externe
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** De ce ultima cautare n-a gasit nimic: null = pur si simplu nu exista rezultate. */
    @Volatile
    var lastProblem: String? = null
        private set

    // cate servicii online au esuat din cauza retelei la ultima cautare
    @Volatile
    private var networkFailures = 0

    suspend fun search(context: Context, query: String): List<Place> = withContext(Dispatchers.IO) {
        val text = query.trim()
        lastProblem = null
        networkFailures = 0
        if (text.length < 2) return@withContext emptyList()
        val found = geocoderSearch(context, text)
            .ifEmpty { photonSearch(text) }
            .ifEmpty { nominatimSearch(text) }
        if (found.isEmpty() && networkFailures >= 2) {
            lastProblem = "Căutarea are nevoie de internet pe telefon (Wi-Fi sau date mobile)."
        }
        found.distinctBy { it.name }
    }

    /** Numele locului de la coordonatele date (ex. dupa GPS sau o atingere pe harta). */
    suspend fun nameOf(context: Context, latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        geocoderName(context, latitude, longitude)
            ?: photonName(latitude, longitude)
            ?: nominatimName(latitude, longitude)
    }

    // ---------- Geocoder (Android) ----------

    @Suppress("DEPRECATION")
    private fun geocoderSearch(context: Context, query: String): List<Place> = try {
        if (!Geocoder.isPresent()) emptyList()
        else Geocoder(context, locale).getFromLocationName(query, 5).orEmpty()
            .filter { it.hasLatitude() && it.hasLongitude() }
            .map { Place(shortName(it), it.latitude, it.longitude) }
    } catch (e: Exception) {
        SafeLog.w(TAG, "geocoder search failed", e)
        emptyList()
    }

    @Suppress("DEPRECATION")
    private fun geocoderName(context: Context, latitude: Double, longitude: Double): String? = try {
        if (!Geocoder.isPresent()) null
        else Geocoder(context, locale).getFromLocation(latitude, longitude, 1)?.firstOrNull()?.let(::shortName)
    } catch (e: Exception) {
        SafeLog.w(TAG, "geocoder reverse failed", e)
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

    // ---------- Photon (komoot, date OpenStreetMap) ----------

    private fun photonSearch(query: String): List<Place> = try {
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "5")
            .build()
        val body = get(url.toString())
        if (body == null) emptyList()
        else JsonParser().parse(body).asJsonObject.getAsJsonArray("features").mapNotNull { feature ->
            val o = feature.asJsonObject
            val coordinates = o.getAsJsonObject("geometry")?.getAsJsonArray("coordinates") ?: return@mapNotNull null
            val properties = o.getAsJsonObject("properties")
            Place(photonLabel(properties), coordinates[1].asDouble, coordinates[0].asDouble)
        }
    } catch (e: Exception) {
        SafeLog.w(TAG, "photon search failed", e)
        emptyList()
    }

    private fun photonName(latitude: Double, longitude: Double): String? = try {
        val url = "https://photon.komoot.io/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", latitude.toString())
            .addQueryParameter("lon", longitude.toString())
            .build()
        get(url.toString())?.let { body ->
            JsonParser().parse(body).asJsonObject.getAsJsonArray("features")
                ?.firstOrNull()?.asJsonObject?.getAsJsonObject("properties")?.let(::photonLabel)
                ?.takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        SafeLog.w(TAG, "photon reverse failed", e)
        null
    }

    private fun photonLabel(properties: com.google.gson.JsonObject?): String {
        if (properties == null) return ""
        fun field(name: String) = properties.get(name)?.takeIf { !it.isJsonNull }?.asString
        return listOfNotNull(
            field("name"),
            field("city") ?: field("county"),
            field("state"),
            field("country")?.takeIf { field("countrycode") != "RO" }
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(3).joinToString(", ")
    }

    // ---------- Nominatim (OpenStreetMap) ----------

    private fun nominatimSearch(query: String): List<Place> = try {
        val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "5")
            .addQueryParameter("accept-language", "ro")
            .build()
        val body = get(url.toString()) ?: return emptyList()
        JsonParser().parse(body).asJsonArray.mapNotNull { element ->
            val o = element.asJsonObject
            val lat = o.get("lat")?.asString?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = o.get("lon")?.asString?.toDoubleOrNull() ?: return@mapNotNull null
            Place(shorten(o.get("display_name")?.asString.orEmpty()), lat, lon)
        }
    } catch (e: Exception) {
        SafeLog.w(TAG, "nominatim search failed", e)
        emptyList()
    }

    private fun nominatimName(latitude: Double, longitude: Double): String? = try {
        val url = "https://nominatim.openstreetmap.org/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", latitude.toString())
            .addQueryParameter("lon", longitude.toString())
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("zoom", "14")
            .addQueryParameter("accept-language", "ro")
            .build()
        get(url.toString())?.let { body ->
            JsonParser().parse(body).asJsonObject.get("display_name")?.asString?.let(::shorten)
        }
    } catch (e: Exception) {
        SafeLog.w(TAG, "nominatim reverse failed", e)
        null
    }

    /** GET catre un serviciu public; null daca nu raspunde (retea) sau refuza. */
    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            // politica OpenStreetMap / Nominatim: aplicatia se identifica unic
            .header("User-Agent", OsmConfig.USER_AGENT)
            .header("Accept-Language", "ro,en")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string()
                else {
                    SafeLog.w(TAG, "$url -> HTTP ${response.code}")
                    null
                }
            }
        } catch (e: java.io.IOException) {
            networkFailures += 1
            SafeLog.w(TAG, "$url: fara retea", e)
            null
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
