package com.example.myandroidapp.maps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

/** Un punct al traseului: locul unei postari, in ordinea vizitei. */
data class RoutePoint(val latitude: Double, val longitude: Double, val title: String)

/**
 * Traseul unei calatorii: marcaj pentru fiecare loc, unite in ordinea vizitelor; harta se
 * incadreaza pe tot traseul. Previzualizare fixa (fara gesturi) in pagina calatoriei.
 */
@Composable
fun RouteMap(points: List<RoutePoint>, modifier: Modifier = Modifier, routeColor: Int = 0xFF1E88E5.toInt()) {
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = rememberMapLibre { ready ->
        ready.cameraPosition = CameraPosition.Builder()
            .target(LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE))
            .zoom(5.5)
            .build()
        ready.uiSettings.setAllGesturesEnabled(false)
        map = ready
    }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { problem = OsmConfig.tileProblem() }

    LaunchedEffect(map, points) {
        val ready = map ?: return@LaunchedEffect
        ready.removeAnnotations()
        val latLngs = points.map { LatLng(it.latitude, it.longitude) }
        if (latLngs.size > 1) {
            ready.addPolyline(PolylineOptions().addAll(latLngs).color(routeColor).width(4f))
        }
        points.forEachIndexed { index, point ->
            ready.addMarker(MarkerOptions().position(latLngs[index]).title("${index + 1}. ${point.title}"))
        }
        when {
            latLngs.size == 1 -> ready.moveCamera(CameraUpdateFactory.newLatLngZoom(latLngs[0], 11.0))
            latLngs.size > 1 -> mapView.post {
                ready.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(latLngs).build(), 80))
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        problem?.let { MapProblem(it, Modifier.align(Alignment.BottomCenter)) }
    }
}
