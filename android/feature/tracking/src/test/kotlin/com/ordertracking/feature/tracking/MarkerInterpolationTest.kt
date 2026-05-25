package com.ordertracking.feature.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkerInterpolationTest {

    @Test
    fun `interpolates linearly between two positions`() {
        val (lat, lng) = MarkerInterpolation.interpolatePosition(0.0, 0.0, 10.0, 20.0, fraction = 0.5f)
        assertEquals(5.0, lat, 1e-9)
        assertEquals(10.0, lng, 1e-9)
    }

    @Test
    fun `fraction is clamped to the 0 to 1 range`() {
        val (lat, _) = MarkerInterpolation.interpolatePosition(0.0, 0.0, 10.0, 0.0, fraction = 1.5f)
        assertEquals(10.0, lat, 1e-9)
    }

    @Test
    fun `bearing wraps the short way across the 360-0 boundary`() {
        // 350deg -> 10deg should rotate +20deg forward through 0, not -340deg backward.
        val halfway = MarkerInterpolation.shortestAngleLerp(fromDegrees = 350f, toDegrees = 10f, fraction = 0.5f)
        assertEquals(0f, halfway, 1e-3f)
    }

    @Test
    fun `bearing interpolates normally when there is no wrap`() {
        val quarter = MarkerInterpolation.shortestAngleLerp(fromDegrees = 0f, toDegrees = 90f, fraction = 0.25f)
        assertEquals(22.5f, quarter, 1e-3f)
    }
}
