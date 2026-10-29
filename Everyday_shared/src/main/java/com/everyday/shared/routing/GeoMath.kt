package com.everyday.shared.routing

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Geographic calculations kept free of Android Location/graphics dependencies. */
object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val sinLat = sin(dLat / 2.0)
        val sinLon = sin(dLon / 2.0)
        val h = (sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon).coerceIn(0.0, 1.0)
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(h))
    }

    /** Initial bearing in degrees clockwise from north, normalized to [0, 360). */
    fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        if (from == to) return 0.0
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    fun normalizeDegrees(value: Double): Double {
        var normalized = value % 360.0
        if (normalized < 0.0) normalized += 360.0
        return normalized
    }

    /** Clockwise turn from [fromDegrees] to [toDegrees] in [-180, 180]. */
    fun signedTurnDegrees(fromDegrees: Double, toDegrees: Double): Double {
        var delta = (toDegrees - fromDegrees) % 360.0
        if (delta <= -180.0) delta += 360.0
        if (delta > 180.0) delta -= 360.0
        return delta
    }

    fun polylineDistanceMeters(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (index in 1 until points.size) total += distanceMeters(points[index - 1], points[index])
        return total
    }

    internal fun nearlyEqual(a: Double, b: Double, epsilon: Double = 1e-7): Boolean = abs(a - b) <= epsilon
}

