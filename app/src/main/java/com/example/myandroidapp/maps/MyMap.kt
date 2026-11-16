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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
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
    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        Configuration.getInstance().apply {
            load(app, app.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            // politica serverelor OpenStreetMap: fiecare aplicatie se identifica
            userAgentValue = app.packageName
            // cache in folderul aplicatiei (nu cere permisiuni de stocare)
            osmdroidBasePath = File(app.cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
        initialized = true
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
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            isTilesScaledToDpi = true
            zoomController.setVisibility(
                if (interactive) CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT
                else CustomZoomButtonsController.Visibility.NEVER
            )
            controller.setZoom(zoom)
            controller.setCenter(GeoPoint(latitude, longitude))
            if (!interactive) setOnTouchListener { _, _ -> true }
        }
    }
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
    }
}

/** Compatibilitate cu codul vechi: harta cu marcaj; atingerea muta marcajul. */
@Composable
fun MyMap(lat: Double, long: Double, modifier: Modifier, onMarkerMoved: (Double, Double) -> Unit) {
    OsmMap(latitude = lat, longitude = long, modifier = modifier, onTap = onMarkerMoved)
}
