package com.everyday.everyday_glasses.binocular

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout

/**
 * Strategy interface for binocular mirroring.
 * 
 * Different content types benefit from different mirroring approaches:
 * - Simple Views: Direct Canvas drawing (no copy overhead)
 * - Complex Views with own surfaces: PixelCopy (required for WebView, etc.)
 * 
 * This abstraction allows the BinocularView to use the optimal strategy
 * for each situation.
 */
interface MirrorStrategy {
    /**
     * Initialize the strategy with required views.
     * @param leftContent The container holding left-eye content
     * @param rightSurface The SurfaceView for right-eye mirroring
     * @param activity The activity (needed for PixelCopy)
     */
    fun initialize(leftContent: FrameLayout, rightSurface: SurfaceView, activity: Activity)
    
    /**
     * Perform the mirror operation.
     * @param dirtyRect Optional dirty rectangle for partial updates (null = full update)
     */
    fun mirror(dirtyRect: Rect? = null)
    
    /**
     * Release resources held by this strategy.
     */
    fun release()
    
    /**
     * Whether this strategy requires a SurfaceView for the right eye.
     * DispatchDraw strategy doesn't need it (draws directly to canvas).
     */
    val requiresSurfaceView: Boolean
}

fun interface PixelCopyPerformanceListener {
    fun onPixelCopyFinished(durationMs: Long, success: Boolean)
}

/**
 * DispatchDraw mirroring strategy.
 * 
 * This strategy draws the same content twice in a single draw pass:
 * once for the left eye (0-640) and once for the right eye (640-1280).
 * 
 * Advantages:
 * - Perfect temporal sync (both eyes render in same frame)
 * - No bitmap allocation or copying
 * - Lowest memory usage
 * - Works with hardware acceleration
 * 
 * Limitations:
 * - Does NOT work for Views that render to their own surface (WebView, SurfaceView, VideoView)
 * - Those views need PixelCopyStrategy
 * 
 * This is the DEFAULT and PREFERRED strategy for standard View-based content.
 */
class DispatchDrawStrategy : MirrorStrategy {
    
    private var leftContent: FrameLayout? = null
    
    override val requiresSurfaceView: Boolean = false
    
    override fun initialize(leftContent: FrameLayout, rightSurface: SurfaceView, activity: Activity) {
        this.leftContent = leftContent
        Log.d(TAG, "DispatchDrawStrategy initialized")
    }
    
    override fun mirror(dirtyRect: Rect?) {
        // For DispatchDraw, mirroring happens in BinocularView.dispatchDraw()
        // This method triggers a redraw
        leftContent?.invalidate()
    }
    
    override fun release() {
        leftContent = null
        Log.d(TAG, "DispatchDrawStrategy released")
    }
    
    companion object {
        private const val TAG = "DispatchDrawStrategy"
    }
}

/**
 * PixelCopy mirroring strategy with double-buffering.
 * 
 * This strategy captures the left eye content using PixelCopy and
 * draws it to the right eye's SurfaceView. Uses double-buffering
 * to prevent tearing and synchronization issues.
 * 
 * Advantages:
 * - Works with ANY content including WebView, SurfaceView, VideoView
 * - Captures composited output exactly as displayed
 * 
 * Limitations:
 * - 1-frame latency (right eye shows previous frame)
 * - GPU→CPU→GPU round-trip overhead
 * - Higher memory usage (two bitmaps)
 * 
 * Use this strategy when the content includes Views that render
 * to their own surface.
 */
class PixelCopyStrategy : MirrorStrategy {
    
    private var leftContent: FrameLayout? = null
    private var rightSurface: SurfaceView? = null
    private var activity: Activity? = null
    
    // Double-buffering to prevent tearing
    private var bufferA: Bitmap? = null
    private var bufferB: Bitmap? = null
    private var frontBuffer: Bitmap? = null
    private var backBuffer: Bitmap? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var lastCaptureTime = 0L
    private var isCapturing = false
    private var minCaptureIntervalProvider: () -> Long = { DisplayConfig.MIN_PIXELCOPY_INTERVAL_MS }
    private var performanceListener: PixelCopyPerformanceListener? = null
    
    override val requiresSurfaceView: Boolean = true
    
    override fun initialize(leftContent: FrameLayout, rightSurface: SurfaceView, activity: Activity) {
        this.leftContent = leftContent
        this.rightSurface = rightSurface
        this.activity = activity
        
        // Set up SurfaceView
        rightSurface.setZOrderOnTop(false)
        rightSurface.holder.setFormat(PixelFormat.TRANSLUCENT)
        
        rightSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                setupBuffers(DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
            }
            
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                setupBuffers(width, height)
            }
            
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                releaseBuffers()
            }
        })
        
        Log.d(TAG, "PixelCopyStrategy initialized")
    }
    
    private fun setupBuffers(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        
        synchronized(this) {
            releaseBuffers()
            try {
                bufferA = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bufferB = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                frontBuffer = bufferA
                backBuffer = bufferB
                Log.d(TAG, "Buffers created: ${width}x${height}")
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Failed to create buffers", e)
            }
        }
    }
    
    private fun releaseBuffers() {
        synchronized(this) {
            bufferA?.recycle()
            bufferB?.recycle()
            bufferA = null
            bufferB = null
            frontBuffer = null
            backBuffer = null
        }
    }

    fun setMinCaptureIntervalProvider(provider: () -> Long) {
        minCaptureIntervalProvider = provider
    }

    fun setPerformanceListener(listener: PixelCopyPerformanceListener?) {
        performanceListener = listener
    }
    
    override fun mirror(dirtyRect: Rect?) {
        if (isCapturing) return  // Skip if previous capture still in progress
        
        val currentTime = System.currentTimeMillis()
        val minIntervalMs = minCaptureIntervalProvider.invoke().coerceAtLeast(DisplayConfig.MIN_PIXELCOPY_INTERVAL_MS)
        if (currentTime - lastCaptureTime < minIntervalMs) {
            return  // Rate limiting
        }
        
        val act = activity ?: return
        val back = synchronized(this) { backBuffer } ?: return
        
        // Calculate capture rectangle
        val captureRect = dirtyRect ?: Rect(0, 0, DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
        
        isCapturing = true
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val requestStartTime = System.currentTimeMillis()
                PixelCopy.request(
                    act.window,
                    captureRect,
                    back,
                    { result ->
                        isCapturing = false
                        val durationMs = System.currentTimeMillis() - requestStartTime
                        if (result == PixelCopy.SUCCESS) {
                            lastCaptureTime = System.currentTimeMillis()
                            swapBuffers()
                            drawToSurface()
                            performanceListener?.onPixelCopyFinished(durationMs, true)
                        } else {
                            Log.w(TAG, "PixelCopy failed with result: $result")
                            performanceListener?.onPixelCopyFinished(durationMs, false)
                        }
                    },
                    handler
                )
            } catch (e: Exception) {
                isCapturing = false
                Log.e(TAG, "PixelCopy exception", e)
                performanceListener?.onPixelCopyFinished(0L, false)
            }
        }
    }
    
    private fun swapBuffers() {
        synchronized(this) {
            val temp = frontBuffer
            frontBuffer = backBuffer
            backBuffer = temp
        }
    }
    
    private fun drawToSurface() {
        val surface = rightSurface ?: return
        val bitmap = synchronized(this) { frontBuffer } ?: return
        
        var canvas: Canvas? = null
        try {
            canvas = surface.holder.lockCanvas()
            canvas?.drawBitmap(bitmap, 0f, 0f, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing to surface", e)
        } finally {
            canvas?.let {
                try {
                    surface.holder.unlockCanvasAndPost(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Error unlocking canvas", e)
                }
            }
        }
    }
    
    override fun release() {
        releaseBuffers()
        leftContent = null
        rightSurface = null
        activity = null
        Log.d(TAG, "PixelCopyStrategy released")
    }
    
    companion object {
        private const val TAG = "PixelCopyStrategy"
    }
}

/**
 * Composite mirroring strategy.
 * 
 * This strategy intelligently combines DispatchDraw and PixelCopy:
 * - Uses DispatchDraw for standard View content (efficient, perfect sync)
 * - Falls back to PixelCopy when complex surfaces are detected
 * 
 * The strategy automatically detects when PixelCopy is needed based on
 * the types of Views in the content hierarchy.
 */
class CompositeStrategy : MirrorStrategy {
    
    private var leftContent: FrameLayout? = null
    private var rightSurface: SurfaceView? = null
    private var activity: Activity? = null
    
    private val dispatchDrawStrategy = DispatchDrawStrategy()
    private val pixelCopyStrategy = PixelCopyStrategy()
    
    private var activeStrategy: MirrorStrategy = dispatchDrawStrategy
    private var hasComplexContent = false
    
    override val requiresSurfaceView: Boolean = true  // Need it for potential PixelCopy
    
    override fun initialize(leftContent: FrameLayout, rightSurface: SurfaceView, activity: Activity) {
        this.leftContent = leftContent
        this.rightSurface = rightSurface
        this.activity = activity
        
        dispatchDrawStrategy.initialize(leftContent, rightSurface, activity)
        pixelCopyStrategy.initialize(leftContent, rightSurface, activity)
        
        // Initially use DispatchDraw
        activeStrategy = dispatchDrawStrategy
        
        Log.d(TAG, "CompositeStrategy initialized")
    }
    
    /**
     * Notify the strategy that complex content (WebView, SurfaceView, etc.)
     * has been added to the view hierarchy.
     */
    fun setHasComplexContent(hasComplex: Boolean) {
        if (hasComplexContent != hasComplex) {
            hasComplexContent = hasComplex
            activeStrategy = if (hasComplex) pixelCopyStrategy else dispatchDrawStrategy
            Log.d(TAG, "Switched to ${if (hasComplex) "PixelCopy" else "DispatchDraw"} strategy")
        }
    }

    fun setPixelCopyMinIntervalProvider(provider: () -> Long) {
        pixelCopyStrategy.setMinCaptureIntervalProvider(provider)
    }

    fun setPixelCopyPerformanceListener(listener: PixelCopyPerformanceListener?) {
        pixelCopyStrategy.setPerformanceListener(listener)
    }
    
    /**
     * Check the view hierarchy for complex content.
     * Call this after adding/removing views.
     */
    fun detectComplexContent() {
        val content = leftContent ?: return
        val hasComplex = containsComplexViews(content)
        setHasComplexContent(hasComplex)
    }
    
    private fun containsComplexViews(view: View): Boolean {
        // Check if this view renders to its own surface
        val viewClass = view.javaClass.name
        val isBrowserWebView = viewClass.contains("BrowserWidget\$MyWebView")
        if ((!isBrowserWebView && viewClass.contains("WebView")) ||
            viewClass.contains("SurfaceView") ||
            viewClass.contains("TextureView") ||
            viewClass.contains("VideoView")) {
            return true
        }
        
        // Recursively check children
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsComplexViews(view.getChildAt(i))) {
                    return true
                }
            }
        }
        
        return false
    }
    
    override fun mirror(dirtyRect: Rect?) {
        activeStrategy.mirror(dirtyRect)
    }
    
    override fun release() {
        dispatchDrawStrategy.release()
        pixelCopyStrategy.release()
        leftContent = null
        rightSurface = null
        activity = null
        Log.d(TAG, "CompositeStrategy released")
    }
    
    companion object {
        private const val TAG = "CompositeStrategy"
    }
}
