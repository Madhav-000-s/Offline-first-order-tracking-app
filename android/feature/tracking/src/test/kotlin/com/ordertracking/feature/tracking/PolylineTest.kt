package com.ordertracking.feature.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class PolylineTest {

    @Test
    fun `decodes the canonical Google polyline example`() {
        // https://developers.google.com/maps/documentation/utilities/polylinealgorithm
        val points = decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")

        assertEquals(3, points.size)
        assertEquals(38.5, points[0].first, 1e-4)
        assertEquals(-120.2, points[0].second, 1e-4)
        assertEquals(40.7, points[1].first, 1e-4)
        assertEquals(-120.95, points[1].second, 1e-4)
        assertEquals(43.252, points[2].first, 1e-3)
        assertEquals(-126.453, points[2].second, 1e-3)
    }

    @Test
    fun `empty string decodes to no points`() {
        assertEquals(emptyList<Pair<Double, Double>>(), decodePolyline(""))
    }
}
