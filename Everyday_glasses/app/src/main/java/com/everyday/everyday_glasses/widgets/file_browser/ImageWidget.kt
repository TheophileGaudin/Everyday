package com.everyday.everyday_glasses.widgets.file_browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.content.Context
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.persistence.DashboardPersistence.putCommonFields
import com.everyday.everyday_glasses.persistence.DashboardPersistence.parseCommonFields
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetDescriptor
import com.everyday.everyday_glasses.runtime.WidgetInstancePolicy
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetType
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import org.json.JSONObject
import java.io.File

class ImageWidget(
    x: Float,
    y: Float,
    private val imagePath: String
) : BaseWidget(ImagePlugin.TYPE, x, y, 300f, 300f) { // Default size, will adjust to aspect ratio

    private var bitmap: Bitmap? = null
    private val srcRect = Rect()
    private val destRect = RectF()
    private var aspectRatio = 1f

    companion object {
        private const val TAG = "ImageWidget"
    }

    init {
        loadImage()
        // Adjust initial size to match aspect ratio, keeping width fixed at 300f
        widgetHeight = widgetWidth / aspectRatio
        updateBaseBounds()
    }

    private fun loadImage() {
        try {
            // Load bitmap bounds first to get dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(imagePath, options)
            
            val imageWidth = options.outWidth
            val imageHeight = options.outHeight
            
            if (imageWidth > 0 && imageHeight > 0) {
                aspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
            }

            // Now load the actual bitmap
            // We might want to sample it down if it's huge, but for now let's load it
            bitmap = BitmapFactory.decodeFile(imagePath)
            
            bitmap?.let {
                srcRect.set(0, 0, it.width, it.height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image: $imagePath", e)
        }
    }

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }

        // Draw background
        canvas.drawRect(widgetBounds, backgroundPaint)

        // Draw image
        bitmap?.let { bmp ->
            destRect.set(contentBounds)
            canvas.drawBitmap(bmp, srcRect, destRect, null)
        }

        // Draw border if hovered
        if (shouldShowBorder()) {
            canvas.drawRect(widgetBounds, hoverBorderPaint)
            drawResizeHandle(canvas)
            
            if (shouldShowBorderButtons()) {
                drawBorderButtons(canvas)
            }
        }
    }

    override fun onDrag(dx: Float, dy: Float, screenWidth: Float, screenHeight: Float) {
        super.onDrag(dx, dy, screenWidth, screenHeight)
        
        if (baseState == BaseState.RESIZING) {
            // Enforce aspect ratio during resize
            // We'll prioritize the larger dimension change or just width
            
            // Recalculate height based on width and aspect ratio
            widgetHeight = widgetWidth / aspectRatio
            
            // Ensure we don't exceed screen bounds
             if (y + widgetHeight > screenHeight) {
                 // If height exceeds screen, clamp height and adjust width
                 widgetHeight = screenHeight - y
                 widgetWidth = widgetHeight * aspectRatio
             }
             
             updateBaseBounds()
        }
    }

    fun getImagePath(): String = imagePath
}
/** Puts images on the dashboard, and deletes an imported image file once no widget shows it. */
class ImagePlugin(
    private val services: WidgetContext,
) : WidgetPlugin {
    private val context: Context get() = services.androidContext
    private val all: () -> List<ImageWidget> = { services.widgets(TYPE).filterIsInstance<ImageWidget>() }
    override val lifecycle = WidgetLifecycleRegistration.multiple(
        ImageWidget::class, ImageWidgetState.Codec,
        capture = ::capture,
        restore = ::restore,
        beforeRemove = ::deleteFileIfUnused,
    )

    /** A missing image file is skipped, so one bad path does not block the rest of the dashboard. */
    private fun restore(state: ImageWidgetState): ImageWidget? {
        if (!File(state.imagePath).exists()) {
            Log.w(TAG, "Skipping missing image widget file: ${state.imagePath}")
            return null
        }
        val widget = ImageWidget(state.x, state.y, state.imagePath)
        services.captureRestore.restoreCommonFields(widget, state, services.viewportWidth, services.viewportHeight)
        return widget
    }

    /** An image without a file is not saved. */
    private fun capture(widget: ImageWidget): ImageWidgetState? {
        val imagePath = widget.getImagePath()
        if (imagePath.isBlank()) return null
        val c = services.captureRestore.extractCommonFields(widget)
        return ImageWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            imagePath = imagePath,
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    /** Opens the image at [path] where the user asked (top left otherwise). */
    fun open(path: String) {
        val position = services.creationPosition
        services.open(TYPE) { ImageWidget(position?.x ?: 0f, position?.y ?: 0f, path) }
    }

    private fun deleteFileIfUnused(widget: ImageWidget) {
        val path = widget.getImagePath()
        val hasSibling = all().any { it !== widget && it.getImagePath() == path }
        if (!hasSibling) ImageWidgetStorage.deleteIfManaged(context, path)
    }

    /** The image as a widget type: its id, its descriptor, how it is saved, and its plugin. */
    companion object : WidgetType {
        val TYPE = WidgetTypeId("image")
        override val descriptor = WidgetDescriptor(
            TYPE,
            "Image",
            WidgetInstancePolicy.MULTIPLE,
            order = 12,
            category = "content"
        )
        override val codec get() = ImageWidgetState.Codec
        override fun create(context: WidgetContext) = ImagePlugin(context)
        private const val TAG = "ImagePlugin"
    }
}

/** What a saved dashboard keeps of an image: the path of its file. */
data class ImageWidgetState(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    val imagePath: String,
    override val isMinimized: Boolean = false,
    override val isFullscreen: Boolean = false,
    override val savedMinX: Float = 0f,
    override val savedMinY: Float = 0f,
    override val savedMinWidth: Float = 0f,
    override val savedMinHeight: Float = 0f,
    override val isPinned: Boolean = false
) : DashboardPersistence.SizedWidgetFields {
    /** Its entry in the saved dashboard file. */
    companion object Codec : WidgetDocument.StateCodec<ImageWidgetState>(ImagePlugin.TYPE, ImageWidgetState::class) {
        override fun toJson(state: ImageWidgetState): JSONObject = JSONObject().apply {
            putCommonFields(state)
            put("imagePath", state.imagePath)
        }

        override fun fromJson(payload: JSONObject): ImageWidgetState? = runCatching {
            val c = parseCommonFields(payload)
            val imagePath = payload.optString("imagePath", "")
            if (imagePath.isBlank()) return null

            ImageWidgetState(
                x = c.x, y = c.y,
                width = payload.getDouble("width").toFloat(),
                height = payload.getDouble("height").toFloat(),
                imagePath = imagePath,
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }.getOrNull()
    }
}