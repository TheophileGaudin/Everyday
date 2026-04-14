package com.everyday.everyday_glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import java.util.Calendar

class LocationWidget(
    private val context: Context,
    x: Float,
    y: Float,
    width: Float,
    height: Float
) : BaseWidget(x, y, width, height) {

    companion object {
        private const val TAG = "LocationWidget"
        private const val ICON_SCALE = 3f
        private const val TOGGLE_MENU_WIDTH = 220f
        private const val TOGGLE_MENU_PADDING = 10f
        private const val TOGGLE_MENU_ROW_HEIGHT = 32f
        private const val TOP_ROW_GAP = 15f
        private const val BOTTOM_ROW_GAP = 12f

        private val countryToCode = mapOf(
            "united states" to "US", "usa" to "US", "us" to "US", "america" to "US",
            "united kingdom" to "GB", "uk" to "GB", "great britain" to "GB", "britain" to "GB", "england" to "GB",
            "france" to "FR", "germany" to "DE", "deutschland" to "DE",
            "spain" to "ES", "espana" to "ES", "italy" to "IT", "italia" to "IT",
            "canada" to "CA", "mexico" to "MX",
            "brazil" to "BR", "brasil" to "BR", "argentina" to "AR",
            "australia" to "AU", "new zealand" to "NZ",
            "japan" to "JP", "china" to "CN", "south korea" to "KR", "korea" to "KR",
            "india" to "IN", "pakistan" to "PK", "bangladesh" to "BD",
            "russia" to "RU", "ukraine" to "UA", "poland" to "PL", "netherlands" to "NL",
            "belgium" to "BE", "switzerland" to "CH", "austria" to "AT",
            "sweden" to "SE", "norway" to "NO", "denmark" to "DK", "finland" to "FI",
            "portugal" to "PT", "greece" to "GR", "turkey" to "TR", "turkiye" to "TR",
            "israel" to "IL", "egypt" to "EG", "south africa" to "ZA",
            "saudi arabia" to "SA", "uae" to "AE", "united arab emirates" to "AE",
            "thailand" to "TH", "vietnam" to "VN", "indonesia" to "ID",
            "malaysia" to "MY", "singapore" to "SG", "philippines" to "PH",
            "ireland" to "IE", "scotland" to "GB", "wales" to "GB",
            "czech republic" to "CZ", "czechia" to "CZ", "hungary" to "HU",
            "romania" to "RO", "bulgaria" to "BG", "croatia" to "HR", "serbia" to "RS",
            "chile" to "CL", "colombia" to "CO", "peru" to "PE", "venezuela" to "VE",
            "taiwan" to "TW", "hong kong" to "HK", "macau" to "MO",
            "new caledonia" to "NC", "french polynesia" to "PF",
            "iceland" to "IS", "luxembourg" to "LU", "monaco" to "MC",
            "nigeria" to "NG", "kenya" to "KE", "morocco" to "MA", "algeria" to "DZ",
            "cuba" to "CU", "puerto rico" to "PR", "jamaica" to "JM",
            "qatar" to "QA", "kuwait" to "KW", "bahrain" to "BH", "oman" to "OM",
            "new guinea" to "PG", "fiji" to "FJ"
        )

        fun countryCodeToFlag(countryCode: String): String {
            if (countryCode.length != 2) return ""
            val firstChar = Character.codePointAt(countryCode.uppercase(), 0) - 'A'.code + 0x1F1E6
            val secondChar = Character.codePointAt(countryCode.uppercase(), 1) - 'A'.code + 0x1F1E6
            return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
        }

        fun countryNameToFlag(countryName: String): String {
            val normalized = countryName.trim().lowercase()
            val code = countryToCode[normalized] ?: return ""
            return countryCodeToFlag(code)
        }
    }

    private enum class LocationElement {
        WEATHER,
        TEMPERATURE,
        LOCATION,
        COUNTRY
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.LEFT
    }

    private val toggleMenuBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DD111827")
        style = Paint.Style.FILL
    }

    private val toggleMenuBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#667FA1C4")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val toggleRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334A6178")
        style = Paint.Style.FILL
    }

    private val toggleRowHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5578A7D5")
        style = Paint.Style.FILL
    }

    private val toggleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
        textAlign = Paint.Align.LEFT
    }

    private val toggleValueOnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8BC34A")
        textSize = 16f
        textAlign = Paint.Align.RIGHT
    }

    private val toggleValueOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        textSize = 16f
        textAlign = Paint.Align.RIGHT
    }

    private var address = "Loading..."
    private var countryText = ""
    private var temperature = "..."
    private var weatherDescription = ""
    private var sunriseEpochMs: Long? = null
    private var sunsetEpochMs: Long? = null

    private var showWeather = true
    private var showTemperature = true
    private var showLocation = true
    private var showCountry = true
    private var showToggleMenu = false
    private var hoveredToggleElement: LocationElement? = null

    override val minimizeLabel: String = "L"

    private var cachedFontSize = 24f
    private var lastWidth = 0f
    private var lastHeight = 0f
    private var lastAddress = ""
    private var lastCountry = ""
    private var lastTemperature = ""
    private var lastIconKey = ""
    private var lastResolvedNightMode: Boolean? = null

    private var cachedWeatherIcon: Bitmap? = null
    private var cachedIconFilename = ""
    private val iconDestRect = RectF()
    private val toggleMenuBounds = RectF()
    private val toggleRowBounds = Array(LocationElement.values().size) { RectF() }

    init {
        updateToggleMenuBounds()
    }

    override fun updateBaseBounds() {
        super.updateBaseBounds()
        updateToggleMenuBounds()
    }

    override fun enterFullscreen() {
        super.enterFullscreen()
        showToggleMenu = false
        updateToggleMenuBounds()
    }

    override fun exitFullscreen() {
        super.exitFullscreen()
        showToggleMenu = false
        updateToggleMenuBounds()
    }

    override fun setFullscreenBounds(screenWidth: Float, screenHeight: Float) {
        super.setFullscreenBounds(screenWidth, screenHeight)
        showToggleMenu = false
        updateToggleMenuBounds()
    }

    private fun splitAddressParts(addr: String): Pair<String, String> {
        val trimmed = addr.trim()
        val commaIndex = trimmed.lastIndexOf(",")
        if (commaIndex < 0) return trimmed to ""

        val town = trimmed.substring(0, commaIndex).trim().ifEmpty { trimmed }
        val countryToken = trimmed.substring(commaIndex + 1).trim()
        if (countryToken.isEmpty()) return town to ""

        val countryDisplay = when {
            looksLikeIsoAlpha2(countryToken) -> countryCodeToFlag(countryToken).ifEmpty { countryToken.uppercase() }
            else -> countryNameToFlag(countryToken).ifEmpty { countryToken }
        }

        return town to countryDisplay
    }

    private fun looksLikeIsoAlpha2(token: String): Boolean {
        val t = token.trim()
        if (t.length != 2) return false
        return t[0].isLetter() && t[1].isLetter()
    }

    fun setLocationData(addr: String, weatherDesc: String, temp: String) {
        val (location, country) = splitAddressParts(addr)
        address = location
        countryText = country
        weatherDescription = weatherDesc
        temperature = temp
        refreshWeatherIconIfNeeded(force = true)
    }

    fun setLocationData(addr: String, weatherInfo: String) {
        val (location, country) = splitAddressParts(addr)
        address = location
        countryText = country

        val parts = weatherInfo.trim()
        val degreeIndex = parts.indexOf("\u00B0").takeIf { it >= 0 } ?: parts.indexOf("\u00C2\u00B0")
        if (degreeIndex > 0) {
            val tempStartIndex = parts.lastIndexOf(" ", degreeIndex)
            if (tempStartIndex > 0) {
                weatherDescription = parts.substring(0, tempStartIndex).trim()
                temperature = parts.substring(tempStartIndex).trim()
            } else {
                weatherDescription = ""
                temperature = parts
            }
        } else {
            weatherDescription = parts
            temperature = ""
        }

        refreshWeatherIconIfNeeded(force = true)
    }

    fun setPermissionDenied() {
        address = "-----"
        countryText = ""
        weatherDescription = ""
        temperature = "-"
        sunriseEpochMs = null
        sunsetEpochMs = null
        lastResolvedNightMode = null
        cachedWeatherIcon = null
        cachedIconFilename = ""
    }

    fun setSunTimes(sunriseEpochMs: Long?, sunsetEpochMs: Long?) {
        this.sunriseEpochMs = sunriseEpochMs?.takeIf { it > 0L }
        this.sunsetEpochMs = sunsetEpochMs?.takeIf { it > 0L }
        refreshWeatherIconIfNeeded(force = true)
    }

    fun setElementVisibility(
        showWeather: Boolean,
        showTemperature: Boolean,
        showLocation: Boolean,
        showCountry: Boolean
    ) {
        this.showWeather = showWeather
        this.showTemperature = showTemperature
        this.showLocation = showLocation
        this.showCountry = showCountry
    }

    fun isWeatherVisible(): Boolean = showWeather
    fun isTemperatureVisible(): Boolean = showTemperature
    fun isLocationVisible(): Boolean = showLocation
    fun isCountryVisible(): Boolean = showCountry

    fun handleToggleMenuTapOrDismiss(px: Float, py: Float): Boolean {
        if (!showToggleMenu) return false

        val tappedElement = LocationElement.values().firstOrNull { toggleRowBounds[it.ordinal].contains(px, py) }
        if (tappedElement != null) {
            toggleElement(tappedElement)
        } else {
            showToggleMenu = false
            hoveredToggleElement = null
        }
        return true
    }

    fun onDoubleTap(px: Float, py: Float): Boolean {
        if (isMinimized || !containsPoint(px, py)) return false
        showToggleMenu = !showToggleMenu
        hoveredToggleElement = null
        return true
    }

    private fun updateWeatherIcon(isNight: Boolean = isNightNow()) {
        if (weatherDescription.isEmpty()) {
            cachedWeatherIcon = null
            cachedIconFilename = ""
            return
        }

        val iconFilename = WeatherIconMapper.getIconForDescription(weatherDescription, isNight)
        if (iconFilename != cachedIconFilename) {
            cachedIconFilename = iconFilename
            cachedWeatherIcon = loadIconFromAssets(iconFilename)
        }
    }

    private fun refreshWeatherIconIfNeeded(force: Boolean = false) {
        val isNight = isNightNow()
        if (!force && lastResolvedNightMode == isNight) {
            return
        }

        lastResolvedNightMode = isNight
        updateWeatherIcon(isNight)
    }

    private fun isNightNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        val sunrise = sunriseEpochMs
        val sunset = sunsetEpochMs
        if (sunrise != null && sunset != null && sunrise < sunset) {
            return nowMs < sunrise || nowMs >= sunset
        }

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowMs
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        return hour < 6 || hour >= 18
    }

    private fun loadIconFromAssets(filename: String): Bitmap? {
        return try {
            context.assets.open(filename).use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load weather icon: $filename", e)
            null
        }
    }

    override fun updateHover(px: Float, py: Float) {
        updateHoverState(px, py)
        hoveredToggleElement = if (showToggleMenu) {
            LocationElement.values().firstOrNull { toggleRowBounds[it.ordinal].contains(px, py) }
        } else {
            null
        }
    }

    private fun calculateOptimalFontSize(): Float {
        val padding = 20f
        val availableHeight = widgetHeight - padding * 2
        val availableWidth = widgetWidth - padding * 2
        val maxFontSizeByHeight = availableHeight / 4.3f

        var fontSize = maxFontSizeByHeight

        val icon = cachedWeatherIcon
        val aspectRatio = if (icon != null && icon.height > 0) {
            icon.width.toFloat() / icon.height.toFloat()
        } else {
            1f
        }
        val largeFontSize = fontSize * ICON_SCALE
        val reserveIconWidth = if (icon != null || weatherDescription.isNotEmpty()) largeFontSize * aspectRatio else 0f

        textPaint.textSize = largeFontSize
        val tempWidth = textPaint.measureText(temperature)

        textPaint.textSize = fontSize
        val locationWidth = textPaint.measureText(address)
        val countryWidth = textPaint.measureText(countryText)
        val topGap = if (reserveIconWidth > 0f) TOP_ROW_GAP else 0f
        val bottomGap = if (countryText.isNotEmpty()) BOTTOM_ROW_GAP else 0f
        val topRowWidth = reserveIconWidth + topGap + tempWidth
        val bottomRowWidth = locationWidth + bottomGap + countryWidth
        val maxContentWidth = maxOf(topRowWidth, bottomRowWidth)

        if (maxContentWidth > availableWidth && availableWidth > 0f) {
            fontSize *= availableWidth / maxContentWidth
        }

        return fontSize.coerceIn(8f, 72f)
    }

    private fun updateFontSizeIfNeeded() {
        if (widgetWidth != lastWidth ||
            widgetHeight != lastHeight ||
            address != lastAddress ||
            countryText != lastCountry ||
            temperature != lastTemperature ||
            cachedIconFilename != lastIconKey
        ) {
            cachedFontSize = calculateOptimalFontSize()
            lastWidth = widgetWidth
            lastHeight = widgetHeight
            lastAddress = address
            lastCountry = countryText
            lastTemperature = temperature
            lastIconKey = cachedIconFilename
        }
    }

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }

        canvas.drawRoundRect(widgetBounds, 8f, 8f, backgroundPaint)

        if (shouldShowBorder()) {
            canvas.drawRoundRect(widgetBounds, 8f, 8f, hoverBorderPaint)
        }

        if (shouldShowBorderButtons()) {
            drawBorderButtons(canvas)
            drawResizeHandle(canvas)
        }

        refreshWeatherIconIfNeeded()
        updateFontSizeIfNeeded()

        val smallFontSize = cachedFontSize
        val largeFontSize = smallFontSize * ICON_SCALE
        val lineSpacing = smallFontSize * 0.3f
        val totalContentHeight = largeFontSize + lineSpacing + smallFontSize
        val startY = y + (widgetHeight - totalContentHeight) / 2f
        val centerX = widgetBounds.centerX()

        val icon = cachedWeatherIcon
        val aspectRatio = if (icon != null && icon.height > 0) {
            icon.width.toFloat() / icon.height.toFloat()
        } else {
            1f
        }
        val iconHeight = largeFontSize
        val iconWidth = if (icon != null || weatherDescription.isNotEmpty()) iconHeight * aspectRatio else 0f

        textPaint.textSize = largeFontSize
        val tempWidth = textPaint.measureText(temperature)
        val topGap = if (iconWidth > 0f) TOP_ROW_GAP else 0f
        val topRowWidth = iconWidth + topGap + tempWidth
        val topStartX = centerX - topRowWidth / 2f

        if (showWeather && icon != null) {
            iconDestRect.set(
                topStartX,
                startY,
                topStartX + iconWidth,
                startY + iconHeight
            )
            canvas.drawBitmap(icon, null, iconDestRect, null)
        }

        val tempX = topStartX + iconWidth + topGap
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val tempY = startY + (iconHeight - textHeight) / 2f - fontMetrics.ascent
        if (showTemperature) {
            canvas.drawText(temperature, tempX, tempY, textPaint)
        }

        textPaint.textSize = smallFontSize
        val locationWidth = textPaint.measureText(address)
        val countryWidth = textPaint.measureText(countryText)
        val bottomGap = if (countryText.isNotEmpty()) BOTTOM_ROW_GAP else 0f
        val bottomRowWidth = locationWidth + bottomGap + countryWidth
        val bottomStartX = centerX - bottomRowWidth / 2f
        val addressY = startY + largeFontSize + lineSpacing + smallFontSize

        if (showLocation && address.isNotEmpty()) {
            canvas.drawText(address, bottomStartX, addressY, textPaint)
        }

        if (showCountry && countryText.isNotEmpty()) {
            val countryX = bottomStartX + locationWidth + bottomGap
            canvas.drawText(countryText, countryX, addressY, textPaint)
        }

        if (showToggleMenu) {
            drawToggleMenu(canvas)
        }
    }

    private fun updateToggleMenuBounds() {
        val menuWidth = TOGGLE_MENU_WIDTH.coerceAtMost((widgetWidth - 32f).coerceAtLeast(120f))
        val menuHeight = TOGGLE_MENU_PADDING * 2 + TOGGLE_MENU_ROW_HEIGHT * LocationElement.values().size
        val left = widgetBounds.centerX() - menuWidth / 2f
        val top = contentBounds.top + 8f
        toggleMenuBounds.set(left, top, left + menuWidth, top + menuHeight)

        LocationElement.values().forEachIndexed { index, _ ->
            val rowTop = toggleMenuBounds.top + TOGGLE_MENU_PADDING + index * TOGGLE_MENU_ROW_HEIGHT
            toggleRowBounds[index].set(
                toggleMenuBounds.left + TOGGLE_MENU_PADDING,
                rowTop,
                toggleMenuBounds.right - TOGGLE_MENU_PADDING,
                rowTop + TOGGLE_MENU_ROW_HEIGHT - 4f
            )
        }
    }

    private fun toggleElement(element: LocationElement) {
        when (element) {
            LocationElement.WEATHER -> showWeather = !showWeather
            LocationElement.TEMPERATURE -> showTemperature = !showTemperature
            LocationElement.LOCATION -> showLocation = !showLocation
            LocationElement.COUNTRY -> showCountry = !showCountry
        }
    }

    private fun drawToggleMenu(canvas: Canvas) {
        canvas.drawRoundRect(toggleMenuBounds, 12f, 12f, toggleMenuBackgroundPaint)
        canvas.drawRoundRect(toggleMenuBounds, 12f, 12f, toggleMenuBorderPaint)

        LocationElement.values().forEachIndexed { index, element ->
            val bounds = toggleRowBounds[index]
            val rowPaint = if (hoveredToggleElement == element) toggleRowHoverPaint else toggleRowPaint
            canvas.drawRoundRect(bounds, 8f, 8f, rowPaint)

            val labelY = bounds.centerY() - (toggleTextPaint.descent() + toggleTextPaint.ascent()) / 2f
            canvas.drawText(toggleLabel(element), bounds.left + 10f, labelY, toggleTextPaint)

            val enabled = when (element) {
                LocationElement.WEATHER -> showWeather
                LocationElement.TEMPERATURE -> showTemperature
                LocationElement.LOCATION -> showLocation
                LocationElement.COUNTRY -> showCountry
            }
            val valuePaint = if (enabled) toggleValueOnPaint else toggleValueOffPaint
            canvas.drawText(if (enabled) "On" else "Off", bounds.right - 10f, labelY, valuePaint)
        }
    }

    private fun toggleLabel(element: LocationElement): String = when (element) {
        LocationElement.WEATHER -> "Weather"
        LocationElement.TEMPERATURE -> "Temperature"
        LocationElement.LOCATION -> "Location"
        LocationElement.COUNTRY -> "Country"
    }
}
