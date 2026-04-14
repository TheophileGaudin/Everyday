package com.everyday.everyday_glasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log

class ImageWidget(
    x: Float,
    y: Float,
    private val imagePath: String
) : BaseWidget(x, y, 300f, 300f) { // Default size, will adjust to aspect ratio

    private var bitmap: Bitmap? = null
    private val srcRect = android.graphics.Rect()
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
