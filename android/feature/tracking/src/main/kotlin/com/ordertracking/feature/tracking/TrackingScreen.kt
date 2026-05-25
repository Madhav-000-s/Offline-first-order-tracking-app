package com.ordertracking.feature.tracking

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * Degraded state (no location yet) falls back to whatever the route
 * polyline's first point is rather than an empty map (DESIGN.md §12).
 */
@Composable
fun TrackingScreen(
    state: TrackingUiState,
    onIntent: (TrackingIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameraPositionState = rememberCameraPositionState()

    val fallbackLatLng = state.routePoints.firstOrNull()?.let { LatLng(it.first, it.second) }
    val courierLatLng = state.courierPosition?.let { LatLng(it.lat, it.lng) } ?: fallbackLatLng

    LaunchedEffect(courierLatLng, state.followCamera) {
        if (state.followCamera && courierLatLng != null) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(courierLatLng, 16f)
        }
    }

    Scaffold(modifier = modifier) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                onMapClick = { onIntent(TrackingIntent.UserPanned) },
            ) {
                if (state.routePoints.size >= 2) {
                    Polyline(
                        points = state.routePoints.map { LatLng(it.first, it.second) },
                        color = Color(0xFF1976D2),
                        width = 8f,
                    )
                }
                courierLatLng?.let { position ->
                    Marker(
                        state = MarkerState(position = position),
                        title = "Courier",
                        rotation = state.courierPosition?.bearingDegrees ?: 0f,
                    )
                }
            }

            FloatingActionButton(
                onClick = { onIntent(TrackingIntent.RecenterClicked) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "Recenter")
            }
        }
    }
}
