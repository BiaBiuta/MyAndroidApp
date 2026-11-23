package com.example.myandroidapp.maps

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File

/**
 * Harta aplicatiei: OpenStreetMap prin osmdroid.
 * Gratuita, fara cont si fara cheie API (Google Maps cere facturare). Hartile vizitate raman
 * in cache, deci se vad si offline zonele deja incarcate.
 */
object OsmConfig {
    /**
     * Identificarea aplicatiei la serverele OpenStreetMap (harti si cautare).
     * Politica lor cere un User-Agent unic si identificabil: un nume generic, de tip
     * "com.example...", e refuzat (harta ramane gri, cautarea nu gaseste nimic).
     */
    const val USER_AGENT = "WanderLog/1.0 (Android; proiect universitar UBB Cluj; https://github.com/BiaBiuta/MyAndroidApp)"

    /**
     * Dalele hartii: CARTO "Voyager" (date OpenStreetMap), gratuite pentru proiecte necomerciale,
     * cu atribuire. Serverele voluntare tile.openstreetmap.org blocheaza aplicatiile mobile care
     * nu respecta politica lor (dala "403 Access blocked"), deci nu le mai folosim.
     */
    val tileSource = org.osmdroid.tileprovider.tilesource.XYTileSource(
        "CartoVoyager",
        0, 19, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://d.basemaps.cartocdn.com/rastertiles/voyager/"
        ),
        "© OpenStreetMap contributors, © CARTO"
    )

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        Configuration.getInstance().apply {
            load(app, app.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = USER_AGENT
            // politica OSM: putine descarcari in paralel; ce s-a vazut ramane in cache (si offline)
            tileDownloadThreads = 2.toShort()
            tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
            // cache in folderul aplicatiei (nu cere permisiuni de stocare)
            osmdroidBasePath = File(app.cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
        // dalele "403 Access blocked" descarcate inainte de la tile.openstreetmap.org: le stergem o data
        val prefs = app.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("carto-migrated", false)) {
            runCatching { File(app.cacheDir, "osmdroid").deleteRecursively() }
            prefs.edit().putBoolean("carto-migrated", true).apply()
        }
        initialized = true
    }

    @Volatile
    private var tileCheck: String? = null
    @Volatile
    private var tileCheckedAt = 0L

    /**
     * Verifica o data (la cel mult 1 minut) daca serverul de harti raspunde, ca sa explicam
     * utilizatorului de ce harta e goala. null = in regula.
     */
    suspend fun tileProblem(): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - tileCheckedAt < 60_000) return@withContext tileCheck
        val request = okhttp3.Request.Builder()
            .url("https://a.basemaps.cartocdn.com/rastertiles/voyager/6/35/22.png")
            .header("User-Agent", USER_AGENT)
            .build()
        tileCheck = try {
            checker.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> null
                    response.code == 403 || response.code == 418 ->
                        "Serverul de hărți a refuzat cererea (HTTP ${response.code})."
                    else -> "Serverul de hărți a răspuns cu eroarea ${response.code}."
                }
            }
        } catch (e: java.io.IOException) {
            "Harta are nevoie de internet pe telefon (Wi-Fi sau date mobile). Zonele văzute deja rămân salvate."
        }
        tileCheckedAt = now
        tileCheck
    }

    private val checker by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}

/** Centrul Romaniei: harta porneste de aici cand nu exista inca o locatie. */
const val DEFAULT_LATITUDE = 45.9432
const val DEFAULT_LONGITUDE = 24.9668

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
    val context = LocalContext.current
    val currentOnTap by rememberUpdatedState(onTap)

    val mapView = remember {
        OsmConfig.init(context)
        MapView(context).apply {
            setTileSource(OsmConfig.tileSource)
            setMultiTouchControls(interactive)
            isTilesScaledToDpi = true
            zoomController.setVisibility(
                if (interactive) CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT
                else CustomZoomButtonsController.Visibility.NEVER
            )
            controller.setZoom(zoom)
            controller.setCenter(GeoPoint(latitude, longitude))
            if (!interactive) setOnTouchListener { _, _ -> true }
            // atribuirea ceruta de licenta OpenStreetMap (ODbL)
            overlays.add(org.osmdroid.views.overlay.CopyrightOverlay(context))
        }
    }
    var problem by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { problem = OsmConfig.tileProblem() }
    val marker = remember {
        Marker(mapView).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }
    }
    remember {
        // atingerea hartii = alegerea locului
        mapView.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                val tap = currentOnTap ?: return false
                tap(p.latitude, p.longitude)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = singleTapConfirmedHelper(p)
        }))
        true
    }

    // harta urmeaza ciclul de viata al ecranului (opreste descarcarea cand ecranul nu e vizibil)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // locul s-a schimbat -> mutam marcajul si centram harta pe el
    LaunchedEffect(latitude, longitude, showMarker, markerTitle) {
        val point = GeoPoint(latitude, longitude)
        marker.position = point
        marker.title = markerTitle
        if (showMarker && marker !in mapView.overlays) mapView.overlays.add(marker)
        if (!showMarker) mapView.overlays.remove(marker)
        mapView.controller.animateTo(point)
        mapView.invalidate()
    }

    Box(modifier = modifier.semantics { contentDescription = "Hartă" }) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        if (!interactive && onClick != null) {
            // stratul transparent preia atingerea (harta din previzualizare nu se misca)
            Box(modifier = Modifier.matchParentSize().clickable(onClick = onClick))
        }
        problem?.let { MapProblem(it, Modifier.align(androidx.compose.ui.Alignment.TopCenter)) }
    }
}

/** Compatibilitate cu codul vechi: harta cu marcaj; atingerea muta marcajul. */
@Composable
fun MyMap(lat: Double, long: Double, modifier: Modifier, onMarkerMoved: (Double, Double) -> Unit) {
    OsmMap(latitude = lat, longitude = long, modifier = modifier, onTap = onMarkerMoved)
}

/** Mesaj peste harta cand nu se pot incarca dalele (fara internet / server de harti indisponibil). */
@Composable
fun MapProblem(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = modifier.padding(8.dp)
    ) {
        androidx.compose.material3.Text(
            text,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
