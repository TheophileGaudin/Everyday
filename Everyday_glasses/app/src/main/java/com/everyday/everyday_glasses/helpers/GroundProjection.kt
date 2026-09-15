package com.everyday.everyday_glasses.helpers

import android.graphics.PointF
import com.everyday.everyday_glasses.data_models.DevicePose
import com.everyday.everyday_glasses.data_models.MapGeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * All projection equations and tweakable calibration values live here.
 *
 * The model is deliberately simple:
 * - Location creates a local ENU ground plane around the wearer.
 * - Heading rotates that plane into head-relative right/forward coordinates.
 * - A neutral pinhole projection maps those points into one eye.
 * - TapLink-style relative quaternion anchoring then compensates screen position
 *   from the captured launch pose.
 */
object MapProjectionTuning {
    const val HORIZONTAL_FOV_DEG = 30f
    const val VERTICAL_FOV_DEG = 22.5f

    const val EYE_HEIGHT_M = 1.60f
    const val NEAR_CLIP_M = 0.75f
    const val FAR_CLIP_M = 220f

    const val YAW_OFFSET_DEG = 0f
    const val YAW_SIGN = 1f

    const val PRINCIPAL_X_FRACTION = 0.50f
    const val PRINCIPAL_Y_FRACTION = 0.50f
    const val PROJECTION_SCALE = 1.00f

    // Same formalism as TapLink anchored mode:
    // relative x-axis rotation -> vertical translation
    // relative y-axis rotation -> horizontal translation
    // relative z-axis rotation -> screen roll
    const val ANCHOR_TRANSLATION_SCALE_PX = 1000f
    const val ANCHOR_X_TO_SCREEN_Y_SIGN = 1f
    // A browser panel needs yaw-to-horizontal translation, as TapLink does.
    // The map is different: yaw already rotates the ground geometry, so a
    // second yaw-driven screen translation double-counts the motion and becomes
    // unstable at large left/right scans.
    const val ANCHOR_Y_TO_SCREEN_X_SIGN = 0f
    const val ANCHOR_Z_ROLL_SIGN = 1f

    const val OFFSCREEN_MARGIN_PX = 120f
}

data class ProjectedGroundPoint(
    val x: Float,
    val y: Float,
    val depthM: Float
)

class GroundProjection {
    private var widthPx: Float = 640f
    private var heightPx: Float = 480f
    private var pose: DevicePose = DevicePose()
    private var origin: MapGeoPoint = MapGeoPoint(0.0, 0.0)

    private var yawRad = 0f
    private var focalX = 1f
    private var focalY = 1f
    private var centerX = 320f
    private var centerY = 240f
    private var anchorTranslateX = 0f
    private var anchorTranslateY = 0f
    private var anchorRollRad = 0f

    fun update(width: Int, height: Int, pose: DevicePose, origin: MapGeoPoint) {
        widthPx = width.toFloat().coerceAtLeast(1f)
        heightPx = height.toFloat().coerceAtLeast(1f)
        this.pose = pose
        this.origin = origin

        yawRad = (
            pose.headingDeg * MapProjectionTuning.YAW_SIGN +
                MapProjectionTuning.YAW_OFFSET_DEG
            ) * DEG_TO_RAD
        anchorTranslateX =
            pose.anchorYRad *
                MapProjectionTuning.ANCHOR_TRANSLATION_SCALE_PX *
                MapProjectionTuning.ANCHOR_Y_TO_SCREEN_X_SIGN
        anchorTranslateY =
            pose.anchorXRad *
                MapProjectionTuning.ANCHOR_TRANSLATION_SCALE_PX *
                MapProjectionTuning.ANCHOR_X_TO_SCREEN_Y_SIGN
        anchorRollRad = pose.anchorZRad * MapProjectionTuning.ANCHOR_Z_ROLL_SIGN

        focalX = widthPx /
            (2f * tan(MapProjectionTuning.HORIZONTAL_FOV_DEG * DEG_TO_RAD / 2f)) *
            MapProjectionTuning.PROJECTION_SCALE
        focalY = heightPx /
            (2f * tan(MapProjectionTuning.VERTICAL_FOV_DEG * DEG_TO_RAD / 2f)) *
            MapProjectionTuning.PROJECTION_SCALE
        centerX = widthPx * MapProjectionTuning.PRINCIPAL_X_FRACTION
        centerY = heightPx * MapProjectionTuning.PRINCIPAL_Y_FRACTION
    }

    fun projectGeo(point: MapGeoPoint): ProjectedGroundPoint? {
        val northM = ((point.lat - origin.lat) * METERS_PER_DEG_LAT).toFloat()
        val eastM = ((point.lon - origin.lon) * metersPerDegLon()).toFloat()
        val cosYaw = cos(yawRad)
        val sinYaw = sin(yawRad)
        val rightM = eastM * cosYaw - northM * sinYaw
        val forwardM = northM * cosYaw + eastM * sinYaw
        return projectGround(rightM, forwardM)
    }

    fun projectGround(rightM: Float, forwardM: Float): ProjectedGroundPoint? =
        projectNeutralCameraSpace(
            rightM = rightM,
            upM = -MapProjectionTuning.EYE_HEIGHT_M,
            forwardM = forwardM
        )

    fun projectHorizonPoint(rightM: Float, forwardM: Float): ProjectedGroundPoint? =
        projectNeutralCameraSpace(
            rightM = rightM,
            upM = 0f,
            forwardM = forwardM
        )

    private fun projectNeutralCameraSpace(
        rightM: Float,
        upM: Float,
        forwardM: Float
    ): ProjectedGroundPoint? {
        if (forwardM > MapProjectionTuning.FAR_CLIP_M || forwardM < -MapProjectionTuning.FAR_CLIP_M) {
            return null
        }

        if (forwardM < MapProjectionTuning.NEAR_CLIP_M ||
            forwardM > MapProjectionTuning.FAR_CLIP_M
        ) {
            return null
        }

        val neutralX = centerX + rightM / forwardM * focalX
        val neutralY = centerY - upM / forwardM * focalY
        val anchored = applyAnchorTransform(neutralX, neutralY)
        val margin = MapProjectionTuning.OFFSCREEN_MARGIN_PX
        if (anchored.x < -margin || anchored.x > widthPx + margin ||
            anchored.y < -margin || anchored.y > heightPx + margin
        ) {
            return null
        }

        return ProjectedGroundPoint(anchored.x, anchored.y, forwardM)
    }

    private fun applyAnchorTransform(screenX: Float, screenY: Float): PointF {
        val dx = screenX - centerX
        val dy = screenY - centerY
        val cosRoll = cos(anchorRollRad)
        val sinRoll = sin(anchorRollRad)
        val rolledX = dx * cosRoll - dy * sinRoll
        val rolledY = dx * sinRoll + dy * cosRoll
        return PointF(
            centerX + rolledX + anchorTranslateX,
            centerY + rolledY + anchorTranslateY
        )
    }

    private fun metersPerDegLon(): Double =
        METERS_PER_DEG_LON_AT_EQUATOR * cos(origin.lat * DEG_TO_RAD_DOUBLE)

    companion object {
        private const val METERS_PER_DEG_LAT = 110540.0
        private const val METERS_PER_DEG_LON_AT_EQUATOR = 111320.0
        private const val DEG_TO_RAD = PI.toFloat() / 180f
        private const val DEG_TO_RAD_DOUBLE = PI / 180.0
    }
}
