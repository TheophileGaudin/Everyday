package com.everyday.everyday_glasses.settings

import android.content.res.Resources
import android.graphics.Canvas
import android.util.Log
import com.everyday.everyday_glasses.interaction.commands.AppCommandRegistry
import com.everyday.everyday_glasses.interaction.assistant.AssistantConfiguration
import com.everyday.everyday_glasses.interaction.assistant.AssistantModel
import com.everyday.everyday_glasses.interaction.assistant.AssistantModelSelectionMode
import com.everyday.everyday_glasses.interaction.assistant.AssistantVoiceHoverControl
import com.everyday.everyday_glasses.ui.hover_controls.BaseHoverControl
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.ui.components.DashboardSettings
import com.everyday.everyday_glasses.runtime.GeminiSettingsListener
import com.everyday.everyday_glasses.google.GoogleAuthState
import com.everyday.everyday_glasses.headup_wake.HeadUpWakeManager
import com.everyday.everyday_glasses.ui.hover_controls.HoverControlsLayoutEditor
import com.everyday.everyday_glasses.ui.widgets.helpers.ShortcutsSettingsMenu
import com.everyday.everyday_glasses.ui.widgets.helpers.SpeedometerSettingsMenu
import com.everyday.everyday_glasses.ui.widgets.SpeedometerWidget
import com.everyday.shared.command.AppCommandInvocation

class SettingsController(
    private val resources: Resources,
    defaultSpeedVisibilityThresholdKmh: Float,
    private val notifyTransientContentChanged: () -> Unit,
    private val notifyPersistentContentChanged: () -> Unit,
    private val setSpeedometerForceVisibleWhenIdle: (Boolean) -> Boolean,
    private val isSmartAlignmentEnabled: () -> Boolean,
    private val setSmartAlignmentEnabled: (Boolean) -> Unit,
    private val notifyBrightnessChanged: (Float) -> Unit,
    private val executeBrightnessCommand: (Float) -> Unit,
    private val notifyAdaptiveBrightnessChanged: (Boolean) -> Unit,
    private val notifyHeadUpTimeChanged: (Long) -> Unit,
    private val notifyWakeDurationChanged: (Long) -> Unit,
    private val notifyAngleThresholdChanged: (Float) -> Unit,
    private val notifyHeadsUpEnabledChanged: (Boolean) -> Unit,
    private val notifySmartAlignmentChanged: (Boolean) -> Unit,
    private val notifySpeedVisibilityThresholdChanged: (Float) -> Unit,
    private val notifyUnitSystemModeChanged: (UnitSystemMode) -> Unit,
    private val connectGoogle: () -> Unit,
    private val grantCalendarAccess: () -> Unit,
    private val disconnectGoogle: () -> Unit,
    private val retryGoogleAuth: () -> Unit,
    private val commandRegistry: AppCommandRegistry,
    private val shortcutsProvider: () -> List<AppCommandInvocation>,
    private val onShortcutsChanged: (List<AppCommandInvocation>) -> Unit = {}
) {
    companion object {
        private const val TAG = "SettingsController"
    }

    private var dashboardSettings: DashboardSettings? = null
    private var speedometerSettingsMenu: SpeedometerSettingsMenu? = null
    private var hoverControlsLayoutEditor: HoverControlsLayoutEditor? = null
    private var hoverControlPlacements: List<DashboardPersistence.HoverControlPlacementState>? = null
    private var hasHoverControlPlacements = false
    private var shortcutsSettingsMenu: ShortcutsSettingsMenu? = null
    private var hoverLayoutChangeEmitted = false
    private var hoverDismissalEmitted = false
    private var lastScreenWidth: Float = 0f
    private var lastScreenHeight: Float = 0f

    private var brightnessValue = 1.0f
    private var adaptiveBrightnessEnabled = true
    private var headUpTimeMs = HeadUpWakeManager.DEFAULT_HEAD_UP_HOLD_TIME_MS
    private var wakeDurationMs = HeadUpWakeManager.DEFAULT_WAKE_DURATION_MS
    private var angleThresholdDegrees = HeadUpWakeManager.DEFAULT_PITCH_CHANGE_THRESHOLD
    private var headsUpEnabled = true
    private var googleAuthState = GoogleAuthState.signedOut()
    private var assistantConfiguration: AssistantConfiguration? = null
    private var assistantModels: List<AssistantModel> = emptyList()
    private var assistantDetail: String = "Paste an API key to enable voice"
    var geminiListener: GeminiSettingsListener? = null
    private var speedVisibilityThresholdKmh = defaultSpeedVisibilityThresholdKmh
    private var unitSystemMode = UnitSystemMode.AUTO
    private var speedUnit = SpeedometerWidget.SpeedUnit.KMH

    fun createHoverControlsLayoutEditor(
        screenWidth: Float,
        screenHeight: Float,
        controls: List<BaseHoverControl>
    ) {
        hoverControlsLayoutEditor = HoverControlsLayoutEditor(screenWidth, screenHeight, controls).apply {
            listener = object : HoverControlsLayoutEditor.Listener {
                override fun onLayoutChanged() {
                    hoverLayoutChangeEmitted = true
                    hoverControlPlacements = exportPlacements()
                    hasHoverControlPlacements = true
                    notifyPersistentContentChanged()
                }

                override fun onDismissed() {
                    hoverDismissalEmitted = true
                    notifyTransientContentChanged()
                }
            }
            if (hasHoverControlPlacements) {
                applyPersistedPlacements(hoverControlPlacements)
            }
        }
    }

    fun isHoverControlsLayoutEditorVisible(): Boolean =
        hoverControlsLayoutEditor?.isVisible == true

    fun showHoverControlsLayoutEditor() {
        val editor = hoverControlsLayoutEditor ?: return
        editor.show()
        notifyTransientContentChanged()
    }

    fun getHoverControlPlacements(): List<DashboardPersistence.HoverControlPlacementState>? =
        hoverControlsLayoutEditor?.exportPlacements()
            ?: hoverControlPlacements.takeIf { hasHoverControlPlacements }

    fun applyHoverControlPlacements(placements: List<DashboardPersistence.HoverControlPlacementState>?) {
        val migrated = placements?.map { placement ->
            if (placement.controlId == AssistantVoiceHoverControl.LEGACY_ID) {
                placement.copy(controlId = AssistantVoiceHoverControl.ID)
            } else placement
        }
        hoverControlPlacements = migrated
        hasHoverControlPlacements = true
        hoverControlsLayoutEditor?.applyPersistedPlacements(migrated)
        notifyPersistentContentChanged()
    }

    fun resetHoverControlPlacementsToDefault(notify: Boolean = true) {
        hoverControlPlacements = null
        hasHoverControlPlacements = false
        hoverControlsLayoutEditor?.resetPlacementsToDefault(notify = false)
        if (notify) notifyPersistentContentChanged()
    }

    fun drawHoverControlsLayoutEditor(canvas: Canvas) {
        hoverControlsLayoutEditor?.draw(canvas)
    }

    fun handleHoverControlsLayoutEditorDown(x: Float, y: Float): Boolean {
        val editor = hoverControlsLayoutEditor ?: return false
        if (!editor.isVisible) return false
        editor.onDown(x, y)
        notifyTransientContentChanged()
        return true
    }

    fun handleHoverControlsLayoutEditorMove(x: Float, y: Float): Boolean {
        val editor = hoverControlsLayoutEditor ?: return false
        if (!editor.isVisible) return false
        editor.onMove(x, y)
        notifyTransientContentChanged()
        return true
    }

    fun handleHoverControlsLayoutEditorUp() {
        val editor = hoverControlsLayoutEditor ?: return
        if (!editor.isVisible) return
        editor.onUp()
    }

    fun handleHoverControlsLayoutEditorTap(x: Float, y: Float): Boolean? {
        val editor = hoverControlsLayoutEditor ?: return null
        if (!editor.isVisible) return null
        hoverLayoutChangeEmitted = false
        hoverDismissalEmitted = false
        val consumed = editor.onTap(x, y)
        if (consumed && !hoverLayoutChangeEmitted && !hoverDismissalEmitted) {
            notifyTransientContentChanged()
        }
        hoverLayoutChangeEmitted = false
        hoverDismissalEmitted = false
        return true
    }

    fun handleHoverControlsLayoutEditorDoubleTap(x: Float, y: Float): Boolean? {
        val editor = hoverControlsLayoutEditor ?: return null
        if (!editor.isVisible) return null
        hoverLayoutChangeEmitted = false
        hoverDismissalEmitted = false
        val consumed = editor.onDoubleTap(x, y)
        if (consumed && !hoverLayoutChangeEmitted && !hoverDismissalEmitted) {
            notifyTransientContentChanged()
        }
        hoverLayoutChangeEmitted = false
        hoverDismissalEmitted = false
        return true
    }

    fun createSpeedometerSettingsMenu(screenWidth: Float, screenHeight: Float) {
        lastScreenWidth = screenWidth
        lastScreenHeight = screenHeight
        speedometerSettingsMenu = SpeedometerSettingsMenu(screenWidth, screenHeight).apply {
            setThreshold(speedVisibilityThresholdKmh, notify = false)
            setSpeedUnit(speedUnit)
            listener = object : SpeedometerSettingsMenu.Listener {
                override fun onThresholdChanged(threshold: Float) {
                    if (speedVisibilityThresholdKmh != threshold) {
                        speedVisibilityThresholdKmh = threshold
                        setSpeedometerForceVisibleWhenIdle(speedVisibilityThresholdKmh <= 0f)
                        notifySpeedVisibilityThresholdChanged(threshold)
                    }
                    notifyTransientContentChanged()
                }

                override fun onDismissed() {
                    notifyTransientContentChanged()
                }
            }
        }
    }

    fun toggleDashboardSettings(screenWidth: Float, screenHeight: Float) {
        if (dashboardSettings != null) {
            dashboardSettings = null
            Log.d(TAG, "DashboardSettings removed")
        } else {
            createDashboardSettings(screenWidth, screenHeight)
            Log.d(TAG, "DashboardSettings created")
        }
        notifyTransientContentChanged()
    }

    fun showSpeedometerSettingsMenu() {
        speedometerSettingsMenu?.let { menu ->
            menu.setThreshold(speedVisibilityThresholdKmh, notify = false)
            menu.show()
            notifyTransientContentChanged()
        }
    }

    fun dismissSpeedometerSettingsMenu() {
        speedometerSettingsMenu?.dismiss()
    }

    fun dismissAll() {
        dashboardSettings?.dismiss()
        speedometerSettingsMenu?.dismiss()
        hoverControlsLayoutEditor?.dismiss()
        shortcutsSettingsMenu?.dismiss()
    }

    fun draw(canvas: Canvas) {
        dashboardSettings?.draw(canvas)
        speedometerSettingsMenu?.draw(canvas)
        shortcutsSettingsMenu?.draw(canvas)
    }

    // ==================== Shortcuts menu ====================

    fun isShortcutsSettingsMenuVisible(): Boolean = shortcutsSettingsMenu?.isVisible == true

    fun isDashboardSettingsVisible(): Boolean = dashboardSettings?.isVisible == true

    fun isSpeedometerSettingsMenuVisible(): Boolean = speedometerSettingsMenu?.isVisible == true

    fun showShortcutsSettingsMenu() {
        val screenWidth = lastScreenWidth.takeIf { it > 0f } ?: return
        val screenHeight = lastScreenHeight.takeIf { it > 0f } ?: return
        val menu = ShortcutsSettingsMenu(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            registry = commandRegistry,
            initialShortcuts = shortcutsProvider()
        ).apply {
            listener = object : ShortcutsSettingsMenu.Listener {
                override fun onShortcutsChanged(shortcuts: List<AppCommandInvocation>) {
                    this@SettingsController.onShortcutsChanged(shortcuts)
                    notifyTransientContentChanged()
                }

                override fun onDismissed() {
                    shortcutsSettingsMenu = null
                    notifyTransientContentChanged()
                }
            }
            show()
        }
        shortcutsSettingsMenu = menu
        notifyTransientContentChanged()
    }

    fun handleShortcutsSettingsMenuDown(x: Float, y: Float): Boolean {
        val menu = shortcutsSettingsMenu ?: return false
        if (!menu.isVisible) return false
        menu.onDown(x, y)
        notifyTransientContentChanged()
        return true
    }

    fun handleShortcutsSettingsMenuMove(x: Float, y: Float): Boolean {
        val menu = shortcutsSettingsMenu ?: return false
        if (!menu.isVisible) return false
        menu.onMove(x, y)
        notifyTransientContentChanged()
        return true
    }

    fun handleShortcutsSettingsMenuUp() {
        val menu = shortcutsSettingsMenu ?: return
        if (!menu.isVisible) return
        menu.onUp()
        notifyTransientContentChanged()
    }

    fun handleShortcutsSettingsMenuTap(x: Float, y: Float): Boolean? {
        val menu = shortcutsSettingsMenu ?: return null
        if (!menu.isVisible) return null
        val consumed = menu.onTap(x, y)
        if (consumed) notifyTransientContentChanged()
        return true
    }


    fun handleSpeedometerDown(x: Float, y: Float): Boolean {
        val menu = speedometerSettingsMenu ?: return false
        if (!menu.isVisible) return false
        menu.onDown(x, y)
        notifyTransientContentChanged()
        return true
    }

    fun handleSpeedometerMove(x: Float, y: Float): Boolean {
        val menu = speedometerSettingsMenu ?: return false
        if (!menu.isVisible) return false
        menu.onMove(x, y)
        notifyTransientContentChanged()
        return true
    }

    fun handleSpeedometerUp() {
        val menu = speedometerSettingsMenu ?: return
        if (!menu.isVisible) return
        menu.onUp()
        notifyTransientContentChanged()
    }

    fun handleSpeedometerTap(x: Float, y: Float): Boolean? {
        val menu = speedometerSettingsMenu ?: return null
        if (!menu.isVisible) return null
        return menu.onTap(x, y)
    }

    fun handleDashboardDown(x: Float, y: Float): Boolean {
        val settings = dashboardSettings ?: return false
        if (!settings.isVisible) return false
        settings.onDown(x, y)
        notifyTransientContentChanged()
        return true
    }

    fun handleDashboardMove(x: Float, y: Float): Boolean {
        val settings = dashboardSettings ?: return false
        if (!settings.isVisible) return false
        settings.onMove(x, y)
        notifyTransientContentChanged()
        return true
    }

    fun handleDashboardUp() {
        val settings = dashboardSettings ?: return
        if (!settings.isVisible) return
        settings.onUp()
        notifyTransientContentChanged()
    }

    fun handleDashboardTap(x: Float, y: Float): Boolean? {
        val settings = dashboardSettings ?: return null
        if (!settings.isVisible) return null
        val consumed = settings.onTap(x, y)
        if (consumed) notifyTransientContentChanged()
        return true
    }

    fun setSpeedVisibilityThreshold(thresholdKmh: Float): Boolean {
        val clamped = thresholdKmh.coerceIn(0f, 1f)
        val forceVisibleChanged = setSpeedometerForceVisibleWhenIdle(clamped <= 0f)
        if (speedVisibilityThresholdKmh == clamped) {
            speedometerSettingsMenu?.setThreshold(clamped, notify = false)
            return forceVisibleChanged
        }
        speedVisibilityThresholdKmh = clamped
        speedometerSettingsMenu?.setThreshold(clamped, notify = false)
        notifySpeedVisibilityThresholdChanged(clamped)
        return forceVisibleChanged
    }

    fun getSpeedVisibilityThreshold(): Float = speedVisibilityThresholdKmh

    fun setUnitSystemMode(mode: UnitSystemMode) {
        unitSystemMode = mode
        dashboardSettings?.setUnitSystemMode(mode, notify = false)
    }

    fun setSpeedUnit(unit: SpeedometerWidget.SpeedUnit) {
        speedUnit = unit
        speedometerSettingsMenu?.setSpeedUnit(unit)
    }

    fun setBrightness(value: Float) {
        brightnessValue = value.coerceIn(0f, 1f)
        notifyBrightnessChanged(brightnessValue)
    }

    fun setAdaptiveBrightnessEnabled(enabled: Boolean) {
        adaptiveBrightnessEnabled = enabled
        notifyAdaptiveBrightnessChanged(adaptiveBrightnessEnabled)
    }

    fun setHeadUpTime(ms: Long) {
        headUpTimeMs = ms.coerceIn(
            HeadUpWakeManager.MIN_HEAD_UP_HOLD_TIME_MS,
            HeadUpWakeManager.MAX_HEAD_UP_HOLD_TIME_MS
        )
        notifyHeadUpTimeChanged(headUpTimeMs)
    }

    fun getHeadUpTime(): Long = headUpTimeMs

    fun setWakeDuration(ms: Long) {
        wakeDurationMs = ms.coerceIn(
            HeadUpWakeManager.MIN_WAKE_DURATION_MS,
            HeadUpWakeManager.MAX_WAKE_DURATION_MS
        )
        notifyWakeDurationChanged(wakeDurationMs)
    }

    fun getWakeDuration(): Long = wakeDurationMs

    fun setAngleThreshold(degrees: Float) {
        angleThresholdDegrees = degrees.coerceIn(
            DashboardSettings.MIN_ANGLE_THRESHOLD,
            DashboardSettings.MAX_ANGLE_THRESHOLD
        )
        notifyAngleThresholdChanged(angleThresholdDegrees)
    }

    fun getAngleThreshold(): Float = angleThresholdDegrees

    fun setHeadsUpEnabled(enabled: Boolean) {
        headsUpEnabled = enabled
        notifyHeadsUpEnabledChanged(headsUpEnabled)
    }

    fun getHeadsUpEnabled(): Boolean = headsUpEnabled

    fun setGoogleAuthState(state: GoogleAuthState) {
        googleAuthState = state
        dashboardSettings?.googleAuthState = state
        notifyTransientContentChanged()
    }

    fun setAssistantConfiguration(configuration: AssistantConfiguration?, models: List<AssistantModel>, detail: String) {
        assistantConfiguration = configuration
        assistantModels = models
        assistantDetail = detail
        dashboardSettings?.apply {
            geminiConfigured = configuration != null
            geminiReady = detail == "Gemini ready"
            geminiModelLabel = modelLabel(configuration)
            geminiDetail = detail
        }
        notifyTransientContentChanged()
    }

    private fun modelLabel(configuration: AssistantConfiguration?): String = when {
        configuration == null -> "Recommended - automatic"
        configuration.selectionMode == AssistantModelSelectionMode.AUTO_RECOMMENDED ->
            "Recommended - ${configuration.modelId.removePrefix("gemini-")}"
        else -> configuration.modelId.removePrefix("gemini-")
    }

    private fun cycleAssistantModel() {
        val current = assistantConfiguration ?: return
        val choices = listOf<String?>(null) + assistantModels.map { it.id }
        val currentChoice: String? = if (current.selectionMode == AssistantModelSelectionMode.AUTO_RECOMMENDED) null else current.modelId
        val next = choices[(choices.indexOf(currentChoice).coerceAtLeast(0) + 1) % choices.size]
        geminiListener?.onSelectGeminiModel(next)
    }

    private fun createDashboardSettings(screenWidth: Float, screenHeight: Float) {
        dashboardSettings = DashboardSettings(resources, screenWidth, screenHeight).apply {
            brightnessValue = this@SettingsController.brightnessValue
            adaptiveBrightnessEnabled = this@SettingsController.adaptiveBrightnessEnabled
            setHeadUpTimeMs(this@SettingsController.headUpTimeMs)
            setWakeDurationMs(this@SettingsController.wakeDurationMs)
            setAngleThresholdDegrees(this@SettingsController.angleThresholdDegrees)
            headsUpEnabled = this@SettingsController.headsUpEnabled
            smartAlignmentEnabled = isSmartAlignmentEnabled()
            googleAuthState = this@SettingsController.googleAuthState
            geminiConfigured = assistantConfiguration != null
            geminiReady = assistantDetail == "Gemini ready"
            geminiModelLabel = modelLabel(assistantConfiguration)
            geminiDetail = assistantDetail
            setUnitSystemMode(this@SettingsController.unitSystemMode, notify = false)

            handler = object : DashboardSettings.DashboardSettingsHandler {
                override fun onBrightnessChanged(value: Float) {
                    executeBrightnessCommand(value)
                    notifyTransientContentChanged()
                }

                override fun onUnitSystemModeChanged(mode: UnitSystemMode) {
                    notifyUnitSystemModeChanged(mode)
                    notifyTransientContentChanged()
                }

                override fun onAdaptiveBrightnessChanged(enabled: Boolean) {
                    this@SettingsController.adaptiveBrightnessEnabled = enabled
                    notifyAdaptiveBrightnessChanged(enabled)
                    notifyTransientContentChanged()
                }

                override fun onHeadUpTimeChanged(value: Float) {
                    val ms = headUpTimeValueToMs(value)
                    this@SettingsController.headUpTimeMs = ms
                    notifyHeadUpTimeChanged(ms)
                    notifyTransientContentChanged()
                }

                override fun onWakeDurationChanged(value: Float) {
                    val ms = wakeDurationValueToMs(value)
                    this@SettingsController.wakeDurationMs = ms
                    notifyWakeDurationChanged(ms)
                    notifyTransientContentChanged()
                }

                override fun onAngleThresholdChanged(degrees: Float) {
                    this@SettingsController.angleThresholdDegrees = degrees
                    notifyAngleThresholdChanged(degrees)
                    notifyTransientContentChanged()
                }

                override fun onHeadsUpEnabledChanged(enabled: Boolean) {
                    this@SettingsController.headsUpEnabled = enabled
                    notifyHeadsUpEnabledChanged(enabled)
                    notifyTransientContentChanged()
                }

                override fun onSmartAlignmentChanged(enabled: Boolean) {
                    setSmartAlignmentEnabled(enabled)
                    notifySmartAlignmentChanged(enabled)
                    notifyTransientContentChanged()
                }

                override fun onConnectGoogle() {
                    connectGoogle()
                    notifyTransientContentChanged()
                }

                override fun onPasteGeminiKey() {
                    this@SettingsController.geminiListener?.onPasteGeminiKey()
                }

                override fun onRemoveGeminiKey() {
                    this@SettingsController.geminiListener?.onRemoveGeminiKey()
                }

                override fun onCycleGeminiModel() {
                    cycleAssistantModel()
                }

                override fun onGrantCalendarAccess() {
                    grantCalendarAccess()
                    notifyTransientContentChanged()
                }

                override fun onDisconnectGoogle() {
                    disconnectGoogle()
                    notifyTransientContentChanged()
                }

                override fun onRetryGoogleAuth() {
                    retryGoogleAuth()
                    notifyTransientContentChanged()
                }

                override fun onAppearanceRequested() {
                    showHoverControlsLayoutEditor()
                }

                override fun onShortcutsRequested() {
                    showShortcutsSettingsMenu()
                }

                override fun onDismissed() {
                    dashboardSettings = null
                    Log.d(TAG, "DashboardSettings removed")
                }
            }

            show()
        }
    }
}
