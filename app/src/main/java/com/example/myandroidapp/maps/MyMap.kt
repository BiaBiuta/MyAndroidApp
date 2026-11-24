package com.example.myandroidapp.maps

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.concurrent.TimeUnit

/**
 * Harta aplicatiei: MapLibre (open-source) cu hartile OpenFreeMap (date OpenStreetMap).
 * Gratuit, fara cont si fara cheie API. Google Maps cere facturare, serverele voluntare
 * tile.openstreetmap.org blocheaza aplicatiile mobile ("403 Access blocked"), iar CARTO cere acum
 * cheie API; OpenFreeMap nu are chei si nici limite de afisari.
 * Atribuirea (© OpenStreetMap, OpenMapTiles) apare pe harta, prin butonul "i" al MapLibre.
 */
object OsmConfig {
    /** Stilul hartii (OpenFreeMap "Liberty"). */
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    /** Identificarea aplicatiei la serviciile de harti si cautare (cerinta OpenFreeMap / Nominatim). */
    const val USER_AGENT = "WanderLog/1.0 (Android; proiect universitar UBB Cluj; https://github.com/BiaBiuta/MyAndroidApp)"

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        MapLibre.getInstance(app)
        // toate cererile hartii pleaca cu User-Agent-ul aplicatiei; cache pe disc gestionat de MapLibre
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
                }
                .build()
        )
        initialized = true
    }

    @Volatile
    private var lastCheck: String? = null
    @Volatile
    private var lastCheckAt = 0L

    /**
     * Verifica o data (la cel mult 1 minut) daca serviciul de harti raspunde, ca sa explicam
     * utilizatorului de ce harta e goala. null = in regula.
     */
    suspend fun tileProblem(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < 60_000) return@withContext lastCheck
        val request = Request.Builder().url(STYLE_URL).header("User-Agent", USER_AGENT).build()
        lastCheck = try {
            checker.newCall(request).execute().use { response ->
                if (response.isSuccessful) null else "Serviciul de hărți a răspuns cu eroarea ${response.code}."
            }
        } catch (e: java.io.IOException) {
            "Harta are nevoie de internet pe telefon (Wi-Fi sau date mobile). Zonele văzute deja rămân salvate."
        }
        lastCheckAt = now
        lastCheck
    }

    private val checker by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}

/** Centrul Romaniei: harta porneste de aici cand nu exista inca o locatie. */
const val DEFAULT_LATITUDE = 45.9432
const val DEFAULT_LONGITUDE = 24.9668

/**
 * Un MapView MapLibre legat de ciclul de viata al ecranului (porneste / se opreste odata cu el,
 * se distruge cand dispare din compozitie). Harta e gata cand [onReady] primeste MapLibreMap-ul.
 */
@Composable
fun rememberMapLibre(onReady: (MapLibreMap) -> Unit): MapView {
    val context = LocalContext.current
    val currentOnReady by rememberUpdatedState(onReady)
    val mapView = remember {
        OsmConfig.init(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                map.setStyle(OsmConfig.STYLE_URL) { currentOnReady(map) }
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> {}
            }
        }
        // addObserver reda evenimentele pana la starea curenta (onStart / onResume)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

/**
 * Harta cu un marcaj.
 *  - interactive = true: se poate muta / mari; atingerea alege locul (onTap);
 *  - interactive = false: previzualizare fixa (ex. intr-un formular care se deruleaza);
 *    atingerea apeleaza onClick (ex. deschide alegerea pe ecran complet).
 * showMarker = false: harta fara marcaj (inca nu s-a ales nimic).
 */
@Composable
fun OsmMap(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
    zoom: Double = 14.0,
    showMarker: Boolean = true,
    interactive: Boolean = true,
    markerTitle: String = "",
    onTap: ((latitude: Double, longitude: Double) -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val currentOnTap by rememberUpdatedState(onTap)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = rememberMapLibre { ready ->
        ready.cameraPosition = CameraPosition.Builder().target(LatLng(latitude, longitude)).zoom(zoom).build()
        ready.uiSettings.setAllGesturesEnabled(interactive)
        ready.uiSettings.isCompassEnabled = interactive
        // atingerea hartii = alegerea locului
        ready.addOnMapClickListener { point ->
            val tap = currentOnTap ?: return@addOnMapClickListener false
            tap(point.latitude, point.longitude)
            true
        }
        map = ready
    }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { problem = OsmConfig.tileProblem() }

    // locul s-a schimbat -> mutam marcajul si centram harta pe el
    LaunchedEffect(map, latitude, longitude, showMarker, markerTitle) {
        val ready = map ?: return@LaunchedEffect
        ready.removeAnnotations()
        val point = LatLng(latitude, longitude)
        if (showMarker) ready.addMarker(MarkerOptions().position(point).title(markerTitle))
        ready.animateCamera(CameraUpdateFactory.newLatLng(point))
    }

    Box(modifier = modifier.semantics { contentDescription = "Hartă" }) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        if (!interactive && onClick != null) {
            // stratul transparent preia atingerea (harta din previzualizare nu se misca)
            Box(modifier = Modifier.matchParentSize().clickable(onClick = onClick))
        }
        problem?.let { MapProblem(it, Modifier.align(Alignment.TopCenter)) }
    }
}

/** Compatibilitate cu codul vechi: harta cu marcaj; atingerea muta marcajul. */
@Composable
fun MyMap(lat: Double, long: Double, modifier: Modifier, onMarkerMoved: (Double, Double) -> Unit) {
    OsmMap(latitude = lat, longitude = long, modifier = modifier, onTap = onMarkerMoved)
}

/** Mesaj peste harta cand nu se poate incarca (fara internet / serviciu indisponibil). */
@Composable
fun MapProblem(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.padding(8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
