package com.ordertracking.feature.tracking

/**
 * Google's polyline algorithm, precision 5 -- decodes what the backend
 * encodes at order creation (backend/app/realtime/fixture_routes.py).
 * The client decodes and draws it once; the courier marker snaps to the
 * nearest point on it, which hides GPS jitter for free (DESIGN.md §12).
 */
fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
    val points = mutableListOf<Pair<Double, Double>>()
    var index = 0
    var lat = 0
    var lng = 0
    val length = encoded.length

    while (index < length) {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1F) shl shift)
            shift += 5
        } while (b >= 0x20)
        val deltaLat = if (result and 1 != 0) (result shr 1).inv() else (result shr 1)
        lat += deltaLat

        result = 0
        shift = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1F) shl shift)
            shift += 5
        } while (b >= 0x20)
        val deltaLng = if (result and 1 != 0) (result shr 1).inv() else (result shr 1)
        lng += deltaLng

        points.add((lat / 1e5) to (lng / 1e5))
    }
    return points
}
