package com.ordertracking.feature.tracking

/**
 * Raw 1 Hz positions look like a stuttering teleport (DESIGN.md §12). Pure
 * math, deliberately decoupled from the Maps SDK's LatLng so it's testable
 * with no Android/Robolectric in the loop at all.
 */
object MarkerInterpolation {

    fun interpolatePosition(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, fraction: Float): Pair<Double, Double> {
        val f = fraction.coerceIn(0f, 1f).toDouble()
        return (fromLat + (toLat - fromLat) * f) to (fromLng + (toLng - fromLng) * f)
    }

    /** Shortest-angle wrap, e.g. 350deg -> 10deg rotates +20deg, not -340deg. */
    fun shortestAngleLerp(fromDegrees: Float, toDegrees: Float, fraction: Float): Float {
        var delta = (toDegrees - fromDegrees) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        val result = fromDegrees + delta * fraction.coerceIn(0f, 1f)
        return ((result % 360f) + 360f) % 360f
    }
}
