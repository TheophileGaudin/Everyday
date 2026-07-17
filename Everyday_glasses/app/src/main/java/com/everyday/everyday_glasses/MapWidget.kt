package com.everyday.everyday_glasses

import android.content.Context
import android.graphics.*
import android.location.Location
import android.util.Log
import kotlin.math.*

/** Circular, heading-up minimap with an interactive fullscreen map. */
class MapWidget(
    context: Context,
    x: Float, y: Float, width: Float = 210f, height: Float = 210f
) : BaseWidget(x, y, width, height) {
    override val minimizeLabel = "M"
    override val minWidth = MIN_WIDGET_SIZE
    override val minHeight = MIN_WIDGET_SIZE

    var onMapDataRequested: ((MapGeoPoint) -> Unit)? = null
    private val poiSelector = MapPoiSelector()
    private var poiSelection = MapPoiSelection()
    private val repository = MapRoadRepository(context, { r, p ->
        roads = r
        pois = p
        updatePoiSelection()
        invalidate?.invoke()
    }, { point -> onMapDataRequested?.invoke(point) })
    private var roads = emptyList<MapRoad>()
    private var pois = emptyList<MapPoi>()
    private var position: Location? = null
    private var heading = 0f
    private var pose = DevicePose()
    private val projection = GroundProjection()
    private val poseController = DevicePoseController(
        context.applicationContext,
        onPoseChanged = { value ->
            pose = value
            heading = value.headingDeg
            invalidate?.invoke()
        },
        onStatusChanged = { Log.d(TAG, it) }
    )
    var invalidate: (() -> Unit)? = null
    var mode = MapViewMode.TOP_DOWN; private set
    var zoom = MapZoom.MEDIUM; private set
    private var showLocation = true
    private var showCountry = true
    private var locationLabel = ""
    private var countryCode = ""
    private var showOptions = false

    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150,205,245); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val major = Paint(road).apply { color = Color.rgb(255,205,105) }
    private val halo = Paint(road).apply { color = Color.argb(55,120,200,255) }
    private val user = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255,245,100); style = Paint.Style.FILL }
    private val poiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255,215,80); style = Paint.Style.FILL }
    private val trainBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val trainOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255,215,80); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val trainDetail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
    private val northLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 105, 95)
        textSize = 14f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WidgetTheme.ColorValue.hoverBorder; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WidgetTheme.ColorValue.primaryButton; style = Paint.Style.FILL }
    private val chipHover = Paint(chip).apply { color = WidgetTheme.ColorValue.hoverBorder }
    private val optionsBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val clip = Path()
    private val viewChip = RectF(); private val zoomChip = RectF()
    private val optionsBounds = RectF(); private val locationOptionBounds = RectF(); private val countryOptionBounds = RectF()
    private var hoverX = Float.NEGATIVE_INFINITY; private var hoverY = Float.NEGATIVE_INFINITY

    init {
        poseController.startTopDown()
    }

    fun setLocation(value: Location) {
        position = Location(value)
        poseController.updateLocation(value)
        updatePoiSelection()
        repository.refresh(MapGeoPoint(value.latitude, value.longitude))
        invalidate?.invoke()
    }

    /** Retries through the phone after its RFCOMM connection becomes available. */
    fun requestPhoneMapRefresh() {
        position?.let { repository.refreshFromPhone(MapGeoPoint(it.latitude, it.longitude)) }
    }

    fun setLocationMetadata(location: String, country: String) {
        locationLabel = location; countryCode = country; invalidate?.invoke()
    }

    fun applyProxyMap(snapshot: com.everyday.shared.sync.MapSnapshot) {
        repository.acceptProxy(
            MapGeoPoint(snapshot.center.lat, snapshot.center.lon),
            snapshot.roads.map { MapRoad(it.id, it.highway, it.name, it.points.map { p -> MapGeoPoint(p.lat, p.lon) }) },
            snapshot.pois.map { MapPoi(it.id, it.name, it.category, mapPoiTier(it.category), MapGeoPoint(it.point.lat, it.point.lon)) }
        )
    }

    fun isLocationVisible() = showLocation
    fun isCountryVisible() = showCountry

    fun release() { poseController.stop() }

    fun restorePreferences(modeName: String, zoomName: String, locationVisible: Boolean = true, countryVisible: Boolean = true) {
        zoom = runCatching { MapZoom.valueOf(zoomName) }.getOrDefault(MapZoom.MEDIUM)
        showLocation = locationVisible
        showCountry = countryVisible
        mode = runCatching { MapViewMode.valueOf(modeName) }.getOrDefault(MapViewMode.TOP_DOWN)
        if (mode == MapViewMode.PROJECTED && isFullscreen) poseController.startProjected()
        else { mode = MapViewMode.TOP_DOWN; poseController.startTopDown() }
    }

    override fun onDestroy() { release() }

    override fun onPause() {
        poseController.stop()
    }

    override fun onResume() {
        if (mode == MapViewMode.PROJECTED && isFullscreen) {
            poseController.startProjected()
        } else {
            poseController.startTopDown()
        }
        updatePoiSelection()
        invalidate?.invoke()
    }

    override fun updateHover(px: Float, py: Float) { super.updateHover(px, py); hoverX = px; hoverY = py }

    fun onDoubleTap(px: Float, py: Float): Boolean {
        if (!containsPoint(px, py)) return false
        showOptions = !showOptions; invalidate?.invoke(); return true
    }

    fun onTap(px: Float, py: Float): Boolean {
        if (showOptions) {
            when {
                locationOptionBounds.contains(px, py) -> showLocation = !showLocation
                countryOptionBounds.contains(px, py) -> showCountry = !showCountry
                !optionsBounds.contains(px, py) -> showOptions = false
                else -> return true
            }
            invalidate?.invoke(); return true
        }
        if (isFullscreen) {
            if (viewChip.contains(px, py)) {
                mode = if (mode == MapViewMode.TOP_DOWN) MapViewMode.PROJECTED else MapViewMode.TOP_DOWN
                if (mode == MapViewMode.PROJECTED) poseController.startProjected() else poseController.startTopDown()
                invalidate?.invoke(); return true
            }
            if (zoomChip.contains(px, py)) { zoom = zoom.next(); updatePoiSelection(); invalidate?.invoke(); return true }
        } else if (baseHitTest(px, py) == BaseHitArea.CONTENT) {
            toggleFullscreen(); return true
        }
        return false
    }

    /** Windowed projected maps are deliberately disallowed; zoom is retained. */
    fun enforceWindowedMode() { if (!isFullscreen) { mode = MapViewMode.TOP_DOWN; poseController.startTopDown() } }

    override fun draw(canvas: Canvas) {
        if (isMinimized) { drawMinimized(canvas); return }
        val c = contentBounds
        val labelSpace = if (showLocation && !isFullscreen) 40f else 0f
        val mapBottom = c.bottom - labelSpace
        val cx = c.centerX(); val cy = (c.top + mapBottom) / 2f
        val radius = min(c.width(), mapBottom - c.top) * (if (isFullscreen) .42f else .5f) - 3f
        val pendingLabels = mutableListOf<PoiLabel>()
        clip.reset(); clip.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip)
        position?.let { if (mode == MapViewMode.TOP_DOWN) drawTopDown(canvas, it, cx, cy, radius, pendingLabels) else drawProjected(canvas, it, c) }
            ?: canvas.drawText("Waiting for location", cx, cy, label.apply { textAlign = Paint.Align.CENTER })
        canvas.restore(); canvas.drawCircle(cx, cy, radius, rim)
        if (position != null && mode == MapViewMode.TOP_DOWN) drawNorthIndicator(canvas, cx, cy, radius)
        position?.let { drawOffscreenImportantPois(canvas, it, cx, cy, radius, pendingLabels) }
        drawPoiLabels(canvas, pendingLabels)
        drawLocationFooter(canvas, c, cx)
        if (isFullscreen) drawControls(canvas, c)
        if (showOptions) drawOptions(canvas)
        if (shouldShowBorder()) canvas.drawRoundRect(widgetBounds, 8f, 8f, hoverBorderPaint)
        if (shouldShowBorderButtons()) { drawBorderButtons(canvas); drawResizeHandle(canvas) }
    }

    private fun drawTopDown(canvas: Canvas, loc: Location, cx: Float, cy: Float, radius: Float, pendingLabels: MutableList<PoiLabel>) {
        for (r in roads) drawRoad(canvas, r, loc, cx, cy, radius)
        for (p in poiSelection.visibleInMap.filter { distance(loc, it.point) <= zoom.radiusM }) {
            val q = mapPoint(loc, p.point, cx, cy, radius) ?: continue
            val important = p.tier != MapPoiTier.NORMAL
            if (p.tier != MapPoiTier.STATION) {
                canvas.drawCircle(q.x, q.y, if (important) 5f else 3f, poiPaint)
            }
            pendingLabels += PoiLabel(p, q, important)
        }
        // Screen-stable forward pointer, identical in orientation to MapTest:
        // geometry rotates around it while forward remains at the screen top.
        val marker = Path().apply { moveTo(cx, cy-11); lineTo(cx-7,cy+8); lineTo(cx,cy+4); lineTo(cx+7,cy+8); close() }
        canvas.drawPath(marker, user)
    }

    private fun drawNorthIndicator(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val angle = Math.toRadians(compassRelativeBearingDeg(0f, heading).toDouble())
        val markerRadius = (radius - 10f).coerceAtLeast(0f)
        val x = cx + sin(angle).toFloat() * markerRadius
        val y = cy - cos(angle).toFloat() * markerRadius
        val baseline = y - (northLabel.ascent() + northLabel.descent()) / 2f
        canvas.drawText("N", x, baseline, northLabel)
    }

    private fun drawRoad(canvas: Canvas, r: MapRoad, loc: Location, cx: Float, cy: Float, radius: Float) {
        val p = Path(); var started = false
        for (point in r.points) { val q = mapPoint(loc, point, cx, cy, radius) ?: continue; if (!started) { p.moveTo(q.x,q.y); started=true } else p.lineTo(q.x,q.y) }
        if (!started) return
        val isMajor = r.highway in setOf("motorway","trunk","primary","secondary")
        halo.strokeWidth = if (isMajor) 10f else 6f; canvas.drawPath(p, halo)
        val paint = if (isMajor) major else road; paint.strokeWidth = if (isMajor) 4f else 2.5f; canvas.drawPath(p, paint)
    }

    private fun mapPoint(loc: Location, point: MapGeoPoint, cx: Float, cy: Float, radius: Float): PointF? {
        val d = distance(loc, point); if (d > zoom.radiusM * 1.2f) return null
        val relative = Math.toRadians(compassRelativeBearingDeg(bearing(loc, point), heading).toDouble())
        val scale = radius / zoom.radiusM
        return PointF(cx + sin(relative).toFloat()*d*scale, cy - cos(relative).toFloat()*d*scale)
    }

    private fun drawProjected(canvas: Canvas, loc: Location, c: RectF) {
        projection.update(
            c.width().toInt(),
            c.height().toInt(),
            pose,
            MapGeoPoint(loc.latitude, loc.longitude)
        )
        for (r in roads) {
            val path = Path(); var started=false
            for (point in r.points) {
                if (distance(loc, point) > zoom.radiusM) continue
                val projected = projection.projectGeo(point) ?: continue
                val sx = c.left + projected.x
                val sy = c.top + projected.y
                if(!started){path.moveTo(sx,sy);started=true}else path.lineTo(sx,sy)
            }
            if(started){road.strokeWidth=3f;canvas.drawPath(path,road)}
        }
        canvas.drawCircle(c.centerX(), c.bottom-22f, 7f, user)
    }

    private fun drawOffscreenImportantPois(canvas: Canvas, loc: Location, cx: Float, cy: Float, radius: Float, pendingLabels: MutableList<PoiLabel>) {
        for (poi in poiSelection.important.filter { distance(loc, it.point) > zoom.radiusM }) {
            val a = Math.toRadians(compassRelativeBearingDeg(bearing(loc, poi.point), heading).toDouble())
            val ax=cx+sin(a).toFloat()*(radius-4); val ay=cy-cos(a).toFloat()*(radius-4)
            val arrow=Path().apply { moveTo(ax,ay); lineTo(ax+sin(a+2.5).toFloat()*13,ay-cos(a+2.5).toFloat()*13); lineTo(ax+sin(a-2.5).toFloat()*13,ay-cos(a-2.5).toFloat()*13); close() }
            canvas.drawPath(arrow,poiPaint)
            val labelAnchor = if (poi.tier == MapPoiTier.STATION) {
                PointF(ax - sin(a).toFloat() * 18f, ay + cos(a).toFloat() * 18f)
            } else {
                PointF(ax, ay)
            }
            pendingLabels += PoiLabel(poi, labelAnchor, true)
        }
    }

    private fun drawPoiLabels(canvas: Canvas, pending: List<PoiLabel>) {
        val occupied = mutableListOf<RectF>()
        for (item in pending.sortedByDescending { it.important }) {
            val isStation = item.poi.tier == MapPoiTier.STATION
            if (isStation) {
                drawTrainIcon(canvas, item.anchor.x, item.anchor.y)
                occupied += RectF(item.anchor.x - 11f, item.anchor.y - 13f, item.anchor.x + 11f, item.anchor.y + 14f)
            }
            val text = item.poi.name?.takeIf(String::isNotBlank)
                ?: item.poi.category.substringAfter('=').replace('_', ' ').replaceFirstChar { it.uppercase() }
            label.textAlign = Paint.Align.LEFT
            label.textSize = if (item.important) 14f else 12f
            val maxWidth = (canvas.width - 8).coerceAtLeast(1).toFloat()
            while (label.measureText(text) > maxWidth && label.textSize > 8f) label.textSize -= 1f
            val width = label.measureText(text)
            val fm = label.fontMetrics
            val height = fm.descent - fm.ascent
            val markerGap = if (isStation) 13f else 7f
            val xOffsets = listOf(markerGap, -width - markerGap, markerGap, -width - markerGap, -width / 2f)
            val yOffsets = listOf(-4f, -4f, height + 8f, height + 8f, -12f)
            var chosen: RectF? = null
            var baseline = item.anchor.y
            for (index in xOffsets.indices) {
                val left = (item.anchor.x + xOffsets[index]).coerceIn(4f, (canvas.width - width - 4f).coerceAtLeast(4f))
                val candidateBaseline = (item.anchor.y + yOffsets[index]).coerceIn(4f - fm.ascent, canvas.height - 4f - fm.descent)
                val rect = RectF(left - 2f, candidateBaseline + fm.ascent - 2f, left + width + 2f, candidateBaseline + fm.descent + 2f)
                if (occupied.none { RectF.intersects(it, rect) }) { chosen = rect; baseline = candidateBaseline; break }
            }
            if (chosen == null) {
                var candidateBaseline = (item.anchor.y - 4f).coerceIn(4f - fm.ascent, canvas.height - 4f - fm.descent)
                val left = (item.anchor.x + 7f).coerceIn(4f, (canvas.width - width - 4f).coerceAtLeast(4f))
                repeat(pending.size) {
                    val rect = RectF(left - 2f, candidateBaseline + fm.ascent - 2f, left + width + 2f, candidateBaseline + fm.descent + 2f)
                    if (chosen == null && occupied.none { RectF.intersects(it, rect) }) { chosen = rect; baseline = candidateBaseline }
                    candidateBaseline = (candidateBaseline + height + 5f).coerceAtMost(canvas.height - 4f - fm.descent)
                }
                if (chosen == null) { baseline = candidateBaseline; chosen = RectF(left, baseline + fm.ascent, left + width, baseline + fm.descent) }
            }
            occupied += chosen!!
            canvas.drawText(text, chosen!!.left + 2f, baseline, label)
        }
    }

    /** Front-facing train silhouette designed to remain legible around 18x22 px. */
    private fun drawTrainIcon(canvas: Canvas, cx: Float, cy: Float) {
        val body = RectF(cx - 8f, cy - 10f, cx + 8f, cy + 7f)
        canvas.drawRoundRect(body, 3f, 3f, trainBody)
        canvas.drawRoundRect(body, 3f, 3f, trainOutline)
        canvas.drawRect(cx - 5.5f, cy - 7f, cx - 0.8f, cy - 2f, trainDetail)
        canvas.drawRect(cx + 0.8f, cy - 7f, cx + 5.5f, cy - 2f, trainDetail)
        canvas.drawRect(cx - 5.5f, cy + 1f, cx + 5.5f, cy + 3f, trainDetail)
        canvas.drawCircle(cx - 5f, cy + 8f, 2.3f, trainDetail)
        canvas.drawCircle(cx + 5f, cy + 8f, 2.3f, trainDetail)
        trainOutline.strokeWidth = 2f
        canvas.drawLine(cx - 7f, cy + 12f, cx + 7f, cy + 12f, trainOutline)
        canvas.drawLine(cx - 5f, cy + 9f, cx - 8f, cy + 12f, trainOutline)
        canvas.drawLine(cx + 5f, cy + 9f, cx + 8f, cy + 12f, trainOutline)
    }
    private fun headingCardinal(value: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = ((value + 22.5f) / 45f).toInt() % directions.size
        return directions[index]
    }
    private fun drawLocationFooter(canvas: Canvas, c: RectF, cx: Float) {
        val debugBaseline = c.bottom - 3f
        val locationBaseline = debugBaseline - 18f

        val place = locationLabel
            .take(28)
            .takeIf { showLocation && it.isNotBlank() }
            .orEmpty()

        val flag = countryCode
            .takeIf { showCountry && it.length == 2 }
            ?.let(LocationWidget::countryCodeToFlag)
            .orEmpty()

        // Existing town and nationality line
        if (place.isNotEmpty() || flag.isNotEmpty()) {
            label.textAlign = Paint.Align.LEFT
            label.textSize = 14f
            val placeWidth = label.measureText(place)

            label.textSize = 19f
            val flagWidth = label.measureText(flag)

            val gap = if (place.isNotEmpty() && flag.isNotEmpty()) 5f else 0f
            var drawX = cx - (placeWidth + gap + flagWidth) / 2f

            if (place.isNotEmpty()) {
                label.textSize = 14f
                canvas.drawText(place, drawX, locationBaseline, label)
                drawX += placeWidth + gap
            }

            if (flag.isNotEmpty()) {
                label.textSize = 19f
                canvas.drawText(flag, drawX, locationBaseline, label)
            }
        }
    }

    private fun updatePoiSelection() {
        val loc = position ?: return
        poiSelection = poiSelector.select(MapGeoPoint(loc.latitude, loc.longitude), pois, zoom.radiusM)
    }

    private data class PoiLabel(val poi: MapPoi, val anchor: PointF, val important: Boolean)

    private fun drawControls(canvas: Canvas, c: RectF) {
        val w=150f; val h=38f; val gap=16f; val left=c.centerX()-w-gap/2
        viewChip.set(left,c.bottom-h,left+w,c.bottom); zoomChip.set(left+w+gap,c.bottom-h,left+w*2+gap,c.bottom)
        fun draw(rect:RectF,text:String){canvas.drawRoundRect(rect,10f,10f,if(rect.contains(hoverX,hoverY))chipHover else chip);label.textAlign=Paint.Align.CENTER;canvas.drawText(text,rect.centerX(),rect.centerY()+5,label)}
        draw(viewChip, if(mode==MapViewMode.TOP_DOWN) "View: Top down" else "View: Projected")
        draw(zoomChip,"Zoom: ${zoom.label}"); label.textAlign=Paint.Align.LEFT
    }

    private fun drawOptions(canvas: Canvas) {
        val menuWidth = 170f.coerceAtMost((widgetWidth - 20f).coerceAtLeast(120f)); val rowH = 38f
        optionsBounds.set(widgetBounds.centerX()-menuWidth/2f, contentBounds.top+8f, widgetBounds.centerX()+menuWidth/2f, contentBounds.top+96f)
        locationOptionBounds.set(optionsBounds.left+6f, optionsBounds.top+6f, optionsBounds.right-6f, optionsBounds.top+6f+rowH)
        countryOptionBounds.set(optionsBounds.left+6f, locationOptionBounds.bottom, optionsBounds.right-6f, locationOptionBounds.bottom+rowH)
        canvas.drawRoundRect(optionsBounds,10f,10f,optionsBackground); canvas.drawRoundRect(optionsBounds,10f,10f,rim)
        fun row(bounds:RectF,name:String,enabled:Boolean){canvas.drawRoundRect(bounds,7f,7f,if(bounds.contains(hoverX,hoverY))chipHover else chip);label.textSize=15f;label.textAlign=Paint.Align.LEFT;canvas.drawText(name,bounds.left+9f,bounds.centerY()+5f,label);label.textAlign=Paint.Align.RIGHT;canvas.drawText(if(enabled)"On" else "Off",bounds.right-9f,bounds.centerY()+5f,label)}
        row(locationOptionBounds,"Location",showLocation); row(countryOptionBounds,"Country",showCountry); label.textAlign=Paint.Align.LEFT
    }

    private fun distance(loc:Location,p:MapGeoPoint)=FloatArray(1).also{Location.distanceBetween(loc.latitude,loc.longitude,p.lat,p.lon,it)}[0]
    private fun bearing(loc:Location,p:MapGeoPoint)=FloatArray(2).also{Location.distanceBetween(loc.latitude,loc.longitude,p.lat,p.lon,it)}[1]

    companion object { private const val TAG = "MapWidget" }
}

/** Clockwise bearing on the display relative to the direction the glasses face. */
internal fun compassRelativeBearingDeg(absoluteBearingDeg: Float, headingDeg: Float): Float {
    var relative = (absoluteBearingDeg - headingDeg) % 360f
    if (relative < 0f) relative += 360f
    return relative
}
