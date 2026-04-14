package com.everyday.everyday_glasses.binocular

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.everyday.everyday_glasses.CursorView

/**
 * BinocularView - A ViewGroup that handles binocular display for AR glasses.
 * 
 * This view manages the complexity of displaying content to both eyes:
 * - Left eye (0-640): Contains the actual UI content
 * - Right eye (640-1280): Mirror of the left eye
 */
class BinocularView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val renderCoordinator = BinocularRenderCoordinator { profile ->
        applyRenderProfile(profile)
    }
    
    /**
     * Container for left eye content.
     * XML children are moved here after inflation.
     */
    private lateinit var contentContainer: FrameLayout
    
    /**
     * SurfaceView for right eye (used only with PixelCopy strategy).
     */
    private lateinit var rightEyeSurface: SurfaceView
    
    /**
     * The mirroring strategy.
     */
    private var mirrorStrategy: MirrorStrategy? = null
    
    /**
     * Controls refresh timing and dirty region tracking.
     */
    private var refreshController: RefreshController? = null
    
    /**
     * Whether we're using DispatchDraw mode.
     */
    private var useDispatchDrawMode = true
    
    /**
     * Activity reference (needed for PixelCopy).
     */
    private var activity: Activity? = null
    
    /**
     * Track if internal setup is complete.
     */
    private var internalSetupComplete = false
    
    /**
     * Track if we need to force invalidation.
     */
    private var pendingInvalidation = false
    private var lastAppliedRenderProfile: BinocularRenderProfile? = null
    
    init {
        setBackgroundColor(Color.BLACK)
        setWillNotDraw(false)
        Log.d(TAG, "BinocularView created")
    }
    
    /**
     * Called after XML inflation is complete.
     * This is where we move XML children into our contentContainer.
     */
    override fun onFinishInflate() {
        super.onFinishInflate()
        Log.d(TAG, "onFinishInflate called, child count: $childCount")
        
        // Collect all XML-defined children
        val xmlChildren = mutableListOf<View>()
        for (i in 0 until childCount) {
            xmlChildren.add(getChildAt(i))
        }
        
        // Remove them temporarily
        removeAllViews()
        
        // Create our internal containers
        contentContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
            setBackgroundColor(Color.BLACK)
        }
        
        rightEyeSurface = SurfaceView(context).apply {
            layoutParams = LayoutParams(DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
            visibility = View.GONE
        }
        
        // Add content container and right eye surface
        super.addView(contentContainer, LayoutParams(DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT))
        super.addView(rightEyeSurface, LayoutParams(DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT))
        
        // Keep overlays like the cursor outside the mirrored content tree so they can
        // render once across both eyes without inheriting WebView/PixelCopy cadence.
        for (child in xmlChildren) {
            if (isOverlayChild(child)) {
                Log.d(TAG, "Keeping child ${child.javaClass.simpleName} as binocular overlay")
                super.addView(
                    child,
                    LayoutParams(DisplayConfig.SCREEN_WIDTH, DisplayConfig.SCREEN_HEIGHT)
                )
            } else {
                Log.d(TAG, "Moving child ${child.javaClass.simpleName} to contentContainer")
                contentContainer.addView(child)
            }
        }
        
        // Create refresh controller
        refreshController = RefreshController { dirtyRect ->
            performMirror(dirtyRect)
            // Force the view to redraw in DispatchDraw mode
            if (useDispatchDrawMode && pendingInvalidation) {
                pendingInvalidation = false
                post { 
                    invalidate()
                    // Also invalidate the window to force a display update
                    rootView?.invalidate()
                }
            }
        }
        applyRenderProfile(renderCoordinator.getCurrentProfile())
        
        internalSetupComplete = true
        Log.d(TAG, "Internal setup complete, contentContainer children: ${contentContainer.childCount}")
    }
    
    /**
     * Initialize the binocular view with the activity.
     * Must be called after setContentView().
     */
    fun initialize(activity: Activity) {
        this.activity = activity
        
        if (!internalSetupComplete) {
            Log.e(TAG, "initialize() called before onFinishInflate()!")
            return
        }
        
        mirrorStrategy = CompositeStrategy().also {
            it.initialize(contentContainer, rightEyeSurface, activity)
            it.setPixelCopyMinIntervalProvider { renderCoordinator.getPixelCopyIntervalMs() }
            it.setPixelCopyPerformanceListener(
                PixelCopyPerformanceListener { durationMs, success ->
                    renderCoordinator.reportPixelCopyResult(durationMs, success)
                }
            )
        }
        applyRenderProfile(renderCoordinator.getCurrentProfile())
        
        Log.d(TAG, "BinocularView initialized")
    }

    private fun isOverlayChild(child: View): Boolean = child is CursorView
    
    /**
     * Set the refresh mode.
     */
    fun setRefreshMode(mode: RefreshMode) {
        refreshController?.setMode(mode)
        
        if (mode == RefreshMode.IDLE) {
            post { invalidate() }
        }
    }
    
    /**
     * Get the current refresh mode.
     */
    fun getRefreshMode(): RefreshMode = refreshController?.getMode() ?: RefreshMode.IDLE

    fun isUsingPixelCopyMode(): Boolean = !useDispatchDrawMode

    fun setContentClass(sourceId: String, contentClass: BinocularContentClass, active: Boolean) {
        renderCoordinator.setSourceState(sourceId, contentClass, active)
    }

    fun onMediaFrame(sourceId: String) {
        renderCoordinator.reportMediaFrame(sourceId)
    }

    fun setChargingState(charging: Boolean) {
        renderCoordinator.setChargingState(charging)
    }

    fun onTrimMemory(level: Int) {
        renderCoordinator.onTrimMemory(level)
    }

    fun onLowMemory() {
        renderCoordinator.onLowMemory()
    }

    fun getRenderProfileSummary(): String = renderCoordinator.getSummary()

    fun refreshRenderingStrategy() {
        detectBestStrategy()
    }
    
    /**
     * Notify that content has changed and needs to be mirrored.
     * This aggressively triggers a redraw to ensure the AR glasses display updates.
     */
    fun notifyContentChanged(
        changedView: View? = null,
        contentClass: BinocularContentClass = BinocularContentClass.INTERACTIVE,
        updateType: BinocularUpdateType = BinocularUpdateType.STRUCTURAL
    ) {
        pendingInvalidation = true
        val isStructuralUpdate =
            updateType == BinocularUpdateType.STRUCTURAL &&
                contentClass != BinocularContentClass.MEDIA
        
        // Mark dirty in refresh controller
        refreshController?.markDirty(changedView)
        
        // Force immediate redraw
        if (useDispatchDrawMode && isStructuralUpdate) {
            // Invalidate all children first to ensure they redraw
            invalidateAllChildren(contentContainer)
        }

        // Invalidate ourselves
        invalidate()

        // Also use postInvalidate for thread safety
        postInvalidate()

        if (isStructuralUpdate) {
            // Force the parent window to redraw as well for structural changes.
            rootView?.postInvalidate()
        }
        
        if (isStructuralUpdate) {
            // Structural changes still force an immediate full refresh so layout
            // transitions stay visually atomic across both eyes.
            refreshController?.forceRefresh()
        }
        
        if (isStructuralUpdate) {
            // Request layout only for structural changes.
            requestLayout()
        }
    }
    
    /**
     * Recursively invalidate all children views.
     */
    private fun invalidateAllChildren(view: View) {
        view.invalidate()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                invalidateAllChildren(view.getChildAt(i))
            }
        }
    }
    
    /**
     * Force using PixelCopy strategy.
     */
    fun forcePixelCopyMode() {
        useDispatchDrawMode = false
        rightEyeSurface.visibility = View.VISIBLE
        
        (mirrorStrategy as? CompositeStrategy)?.setHasComplexContent(true)
        
        Log.d(TAG, "Forced PixelCopy mode")
    }
    
    /**
     * Force using DispatchDraw strategy (default).
     */
    fun forceDispatchDrawMode() {
        useDispatchDrawMode = true
        rightEyeSurface.visibility = View.GONE
        
        (mirrorStrategy as? CompositeStrategy)?.setHasComplexContent(false)
        
        Log.d(TAG, "Forced DispatchDraw mode")
    }
    
    /**
     * Auto-detect the best mirroring strategy based on content.
     */
    fun detectBestStrategy() {
        (mirrorStrategy as? CompositeStrategy)?.detectComplexContent()
        
        val hasComplex = containsComplexViews(contentContainer)
        useDispatchDrawMode = !hasComplex
        rightEyeSurface.visibility = if (hasComplex) View.VISIBLE else View.GONE
    }
    
    private fun containsComplexViews(view: View): Boolean {
        val viewClass = view.javaClass.name
        val isBrowserWebView = viewClass.contains("BrowserWidget\$MyWebView")
        if ((!isBrowserWebView && viewClass.contains("WebView")) ||
            viewClass.contains("SurfaceView") ||
            viewClass.contains("TextureView") ||
            viewClass.contains("VideoView")) {
            return true
        }
        
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsComplexViews(view.getChildAt(i))) {
                    return true
                }
            }
        }
        
        return false
    }
    
    private fun performMirror(dirtyRect: Rect?) {
        mirrorStrategy?.mirror(dirtyRect)
    }

    private fun applyRenderProfile(profile: BinocularRenderProfile) {
        if (lastAppliedRenderProfile == profile) return
        lastAppliedRenderProfile = profile
        refreshController?.setMode(profile.refreshMode)
        Log.d(
            TAG,
            "Applied render profile: ${profile.dominantClass} " +
                "refresh=${profile.refreshMode} interval=${profile.continuousFrameIntervalMs}ms " +
                "charging=${profile.isCharging} downshift=${profile.isBatteryDownshifted} " +
                "memoryPressure=${profile.hasMemoryPressure}"
        )
    }
    
    // ==================== Lifecycle ====================
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshController?.start()
        Log.d(TAG, "Attached to window")
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        refreshController?.stop()
        Log.d(TAG, "Detached from window")
    }
    
    fun onResume() {
        refreshController?.start()
    }
    
    fun onPause() {
        // Don't stop the refresh controller on pause to keep display active
        // refreshController?.stop()
    }
    
    fun onDestroy() {
        refreshController?.stop()
        mirrorStrategy?.release()
        activity = null
    }
    
    // ==================== View Hierarchy ====================
    
    /**
     * Redirect programmatic addView calls to contentContainer.
     */
    override fun addView(child: View?, index: Int, params: ViewGroup.LayoutParams?) {
        if (child == null) return
        
        // Before internal setup, let normal addView work (for XML inflation)
        if (!internalSetupComplete) {
            super.addView(child, index, params)
            return
        }
        
        // After setup, redirect to contentContainer
        if (child !== contentContainer && child !== rightEyeSurface && !isOverlayChild(child)) {
            Log.d(TAG, "Redirecting programmatic child ${child.javaClass.simpleName} to contentContainer")
            contentContainer.addView(child, params)
            detectBestStrategy()
        } else {
            super.addView(child, index, params)
        }
    }
    
    /**
     * Get the content container for direct access if needed.
     */
    fun getContentContainer(): FrameLayout = contentContainer
    
    // ==================== Layout ====================
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = DisplayConfig.SCREEN_WIDTH
        val height = DisplayConfig.SCREEN_HEIGHT
        
        if (internalSetupComplete) {
            val eyeWidthSpec = MeasureSpec.makeMeasureSpec(DisplayConfig.EYE_WIDTH, MeasureSpec.EXACTLY)
            val eyeHeightSpec = MeasureSpec.makeMeasureSpec(DisplayConfig.EYE_HEIGHT, MeasureSpec.EXACTLY)
            val screenWidthSpec = MeasureSpec.makeMeasureSpec(DisplayConfig.SCREEN_WIDTH, MeasureSpec.EXACTLY)
            val screenHeightSpec = MeasureSpec.makeMeasureSpec(DisplayConfig.SCREEN_HEIGHT, MeasureSpec.EXACTLY)
            
            contentContainer.measure(eyeWidthSpec, eyeHeightSpec)
            rightEyeSurface.measure(eyeWidthSpec, eyeHeightSpec)
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child !== contentContainer && child !== rightEyeSurface) {
                    child.measure(screenWidthSpec, screenHeightSpec)
                }
            }
        }
        
        setMeasuredDimension(width, height)
    }
    
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!internalSetupComplete) return
        
        // Left eye (0 to 640)
        contentContainer.layout(0, 0, DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
        
        // Right eye (640 to 1280)
        rightEyeSurface.layout(DisplayConfig.EYE_WIDTH, 0, DisplayConfig.SCREEN_WIDTH, DisplayConfig.EYE_HEIGHT)

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child !== contentContainer && child !== rightEyeSurface) {
                child.layout(0, 0, DisplayConfig.SCREEN_WIDTH, DisplayConfig.SCREEN_HEIGHT)
            }
        }
    }
    
    // ==================== Drawing ====================
    
    // Track draw calls for debugging
    private var lastDrawTime = 0L
    private var drawCount = 0
    
    override fun dispatchDraw(canvas: Canvas) {
        if (!internalSetupComplete) {
            super.dispatchDraw(canvas)
            return
        }
        
        // Log draw calls periodically for debugging
        drawCount++
        val now = System.currentTimeMillis()
        if (now - lastDrawTime > 1000) {
            //Log.d(TAG, "dispatchDraw: $drawCount draws in last second")
            drawCount = 0
            lastDrawTime = now
        }
        
        if (useDispatchDrawMode) {
            // Draw to left eye (0-640)
            canvas.save()
            canvas.clipRect(0, 0, DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
            contentContainer.draw(canvas)
            canvas.restore()
            
            // Draw to right eye (640-1280) - same content, translated
            canvas.save()
            canvas.clipRect(DisplayConfig.EYE_WIDTH, 0, DisplayConfig.SCREEN_WIDTH, DisplayConfig.EYE_HEIGHT)
            canvas.translate(DisplayConfig.EYE_WIDTH.toFloat(), 0f)
            contentContainer.draw(canvas)
            canvas.restore()

            val drawingTime = drawingTime
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child !== contentContainer && child !== rightEyeSurface) {
                    drawChild(canvas, child, drawingTime)
                }
            }
        } else {
            super.dispatchDraw(canvas)
        }
    }
    
    companion object {
        private const val TAG = "BinocularView"
    }
}
