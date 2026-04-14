package com.everyday.everyday_glasses

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

/**
 * A file browser widget for navigating and selecting files on the device.
 * Works with cursor-based interaction on AR glasses.
 *
 * Uses MediaStore API on Android 10+ for proper access to media files,
 * with fallback to direct file access on older Android versions.
 *
 * Supports filtering by file type for extensibility (images now, PDFs later, etc.)
 */
class FileBrowserWidget(
    private val context: Context,
    x: Float,
    y: Float,
    width: Float = 400f,
    height: Float = 500f,
    private val fileFilter: FileFilter = FileFilter.IMAGES
) : BaseWidget(x, y, width, height) {

    companion object {
        private const val TAG = "FileBrowserWidget"

        // Layout constants
        private const val HEADER_HEIGHT = 44f
        private const val ITEM_HEIGHT = 48f
        private const val ICON_SIZE = 32f
        private const val PADDING = 12f
        private const val SCROLLBAR_WIDTH = 8f
        private const val NAV_BUTTON_SIZE = 32f
    }

    /**
     * File type filter for the browser
     */
    enum class FileFilter(val extensions: Set<String>, val mimePrefix: String) {
        IMAGES(setOf("jpg", "jpeg", "png", "gif", "webp", "bmp"), "image/"),
        PDF(setOf("pdf"), "application/pdf"),
        ALL(emptySet(), "*/*")
    }

    /**
     * Represents a file or directory entry.
     * Can be backed by either a File (for directories) or a MediaStore URI (for media files).
     */
    data class FileEntry(
        val file: File?,           // For directories and legacy file access
        val uri: Uri? = null,      // For MediaStore-based access (Android 10+)
        val name: String,
        val isDirectory: Boolean,
        val extension: String,
        val absolutePath: String   // Full path for display and file operations
    )

    enum class State {
        IDLE, HOVER_CONTENT, HOVER_BORDER, MOVING, RESIZING
    }

    enum class HitArea {
        NONE, CONTENT, BORDER, CLOSE_BUTTON, FULLSCREEN_BUTTON, MINIMIZE_BUTTON, PIN_BUTTON,
        RESIZE_HANDLE, SCROLLBAR, NAV_BACK, NAV_HOME, FILE_ITEM
    }

    // State
    private var state = State.IDLE
    var onStateChanged: ((State) -> Unit)? = null

    // Callback when a file is selected - provides the FileEntry which contains path and optional URI
    var onFileSelected: ((FileEntry) -> Unit)? = null

    // Current directory and file list
    private var currentDirectory: File = getDefaultDirectory()
    private var entries: List<FileEntry> = emptyList()

    // Scroll state
    private var scrollOffset = 0f
    private var maxScrollOffset = 0f

    // Hover state
    private var hoveredItemIndex = -1
    private var isHoveringBack = false
    private var isHoveringHome = false
    private var isHoveringScrollbar = false

    // Layout bounds
    private val headerBounds = RectF()
    private val backButtonBounds = RectF()
    private val homeButtonBounds = RectF()
    private val pathTextBounds = RectF()
    private val listBounds = RectF()
    private val scrollbarBounds = RectF()
    private val scrollbarThumbBounds = RectF()
    private val itemBounds = mutableListOf<RectF>()

    // Paints
    private val headerPaint = Paint().apply {
        color = Color.parseColor("#1a1a2e")
        style = Paint.Style.FILL
    }

    private val listBackgroundPaint = Paint().apply {
        color = Color.parseColor("#0d0d1a")
        style = Paint.Style.FILL
    }

    private val itemPaint = Paint().apply {
        color = Color.parseColor("#1a1a2e")
        style = Paint.Style.FILL
    }

    private val hoverItemPaint = Paint().apply {
        color = Color.parseColor("#3a3a5e")
        style = Paint.Style.FILL
    }

    private val selectedItemPaint = Paint().apply {
        color = Color.parseColor("#5050AA")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 18f
        isAntiAlias = true
    }

    private val pathTextPaint = Paint().apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 14f
        isAntiAlias = true
    }

    private val iconPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val folderIconPaint = Paint().apply {
        color = Color.parseColor("#FFD54F")  // Yellow for folders
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val imageIconPaint = Paint().apply {
        color = Color.parseColor("#4FC3F7")  // Light blue for images
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val fileIconPaint = Paint().apply {
        color = Color.parseColor("#90A4AE")  // Gray for generic files
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val scrollbarTrackPaint = Paint().apply {
        color = Color.parseColor("#333344")
        style = Paint.Style.FILL
    }

    private val scrollbarThumbPaint = Paint().apply {
        color = Color.parseColor("#6666AA")
        style = Paint.Style.FILL
    }

    private val navButtonPaint = Paint().apply {
        color = Color.parseColor("#3a3a5e")
        style = Paint.Style.FILL
    }

    private val navButtonHoverPaint = Paint().apply {
        color = Color.parseColor("#5050AA")
        style = Paint.Style.FILL
    }

    override val minWidth = 300f
    override val minHeight = 200f
    override val minimizeLabel = "F"

    init {
        updateBaseBounds()
        loadDirectory(currentDirectory)
    }

    private fun getDefaultDirectory(): File {
        // Try common image directories
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        if (dcim.exists() && dcim.canRead()) return dcim

        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        if (pictures.exists() && pictures.canRead()) return pictures

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloads.exists() && downloads.canRead()) return downloads

        // Fallback to external storage root
        val extStorage = Environment.getExternalStorageDirectory()
        if (extStorage.exists() && extStorage.canRead()) return extStorage

        // Last resort: app's files directory (always accessible)
        return File("/sdcard")
    }

    /**
     * Load and display contents of a directory.
     * Uses MediaStore on Android 10+ for media files, with fallback to direct file access.
     */
    fun loadDirectory(directory: File) {
        currentDirectory = directory
        scrollOffset = 0f
        hoveredItemIndex = -1

        entries = try {
            Log.d(TAG, "Loading directory: ${directory.absolutePath}")
            Log.d(TAG, "Directory exists: ${directory.exists()}, canRead: ${directory.canRead()}")

            val resultEntries = mutableListOf<FileEntry>()

            // First, get subdirectories using traditional file access (this still works)
            val files = directory.listFiles()
            Log.d(TAG, "listFiles returned: ${files?.size ?: "null"} items")

            files?.filter { it.isDirectory }?.forEach { dir ->
                resultEntries.add(FileEntry(
                    file = dir,
                    uri = null,
                    name = dir.name,
                    isDirectory = true,
                    extension = "",
                    absolutePath = dir.absolutePath
                ))
            }

            // For media files, use MediaStore on Android 10+ (Scoped Storage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fileFilter == FileFilter.IMAGES) {
                val mediaFiles = queryMediaStoreForImages(directory.absolutePath)
                resultEntries.addAll(mediaFiles)
                Log.d(TAG, "MediaStore returned ${mediaFiles.size} images for ${directory.absolutePath}")
            } else {
                // Fallback for older Android or non-image files
                files?.filter { file ->
                    !file.isDirectory && when (fileFilter) {
                        FileFilter.ALL -> true
                        else -> fileFilter.extensions.contains(file.extension.lowercase())
                    }
                }?.forEach { file ->
                    resultEntries.add(FileEntry(
                        file = file,
                        uri = null,
                        name = file.name,
                        isDirectory = false,
                        extension = file.extension.lowercase(),
                        absolutePath = file.absolutePath
                    ))
                }
            }

            // Sort: directories first, then by name
            resultEntries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } catch (e: Exception) {
            Log.e(TAG, "Error loading directory: ${directory.path}", e)
            emptyList()
        }

        updateScrollBounds()
        Log.d(TAG, "Loaded ${entries.size} entries from ${directory.path}")
    }

    /**
     * Check if the app has storage permissions to read files.
     */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses READ_MEDIA_IMAGES
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 6-12 uses READ_EXTERNAL_STORAGE
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Query MediaStore for images in the specified directory path.
     * This is required on Android 10+ due to Scoped Storage restrictions.
     */
    private fun queryMediaStoreForImages(directoryPath: String): List<FileEntry> {
        val images = mutableListOf<FileEntry>()

        // Check permission first
        val hasPermission = hasStoragePermission()
        Log.d(TAG, "Storage permission granted: $hasPermission (SDK ${Build.VERSION.SDK_INT})")

        if (!hasPermission) {
            Log.w(TAG, "Storage permission NOT granted - MediaStore will return 0 results")
            return images
        }

        try {
            Log.d(TAG, "Android SDK: ${Build.VERSION.SDK_INT}, querying MediaStore for: $directoryPath")

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA,  // Full path
                MediaStore.Images.Media.MIME_TYPE
            )

            // First, let's see ALL images in MediaStore to debug
            context.contentResolver.query(
                collection,
                projection,
                null,  // No filter - get all images
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                Log.d(TAG, "MediaStore total images on device: ${cursor.count}")
                // Log first 5 images to see what paths exist
                var count = 0
                while (cursor.moveToNext() && count < 5) {
                    val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                    Log.d(TAG, "  Sample image $count: $path")
                    count++
                }
            }

            // Now query for images in this specific directory
            val selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf("$directoryPath/%")

            val sortOrder = "${MediaStore.Images.Media.DISPLAY_NAME} ASC"

            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                Log.d(TAG, "MediaStore query returned ${cursor.count} rows for $directoryPath")

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)
                    val path = cursor.getString(dataColumn)

                    // Only include files directly in this directory (not subdirectories)
                    val parentPath = File(path).parent
                    if (parentPath == directoryPath) {
                        val uri = ContentUris.withAppendedId(collection, id)
                        val extension = name.substringAfterLast('.', "").lowercase()

                        images.add(FileEntry(
                            file = null,  // We use URI instead
                            uri = uri,
                            name = name,
                            isDirectory = false,
                            extension = extension,
                            absolutePath = path
                        ))
                        Log.d(TAG, "Found image via MediaStore: $name at $path")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore for images in $directoryPath", e)
        }

        return images
    }

    private fun updateScrollBounds() {
        val visibleHeight = listBounds.height()
        val contentHeight = entries.size * ITEM_HEIGHT
        maxScrollOffset = (contentHeight - visibleHeight).coerceAtLeast(0f)
    }

    override fun updateBaseBounds() {
        super.updateBaseBounds()

        // Header bounds
        headerBounds.set(
            contentBounds.left,
            contentBounds.top,
            contentBounds.right,
            contentBounds.top + HEADER_HEIGHT
        )

        // Navigation buttons in header
        backButtonBounds.set(
            headerBounds.left + PADDING,
            headerBounds.top + (HEADER_HEIGHT - NAV_BUTTON_SIZE) / 2,
            headerBounds.left + PADDING + NAV_BUTTON_SIZE,
            headerBounds.top + (HEADER_HEIGHT + NAV_BUTTON_SIZE) / 2
        )

        homeButtonBounds.set(
            backButtonBounds.right + PADDING / 2,
            backButtonBounds.top,
            backButtonBounds.right + PADDING / 2 + NAV_BUTTON_SIZE,
            backButtonBounds.bottom
        )

        pathTextBounds.set(
            homeButtonBounds.right + PADDING,
            headerBounds.top,
            headerBounds.right - PADDING,
            headerBounds.bottom
        )

        // List bounds (below header)
        listBounds.set(
            contentBounds.left,
            headerBounds.bottom,
            contentBounds.right - SCROLLBAR_WIDTH,
            contentBounds.bottom
        )

        // Scrollbar
        scrollbarBounds.set(
            contentBounds.right - SCROLLBAR_WIDTH,
            headerBounds.bottom,
            contentBounds.right,
            contentBounds.bottom
        )

        updateScrollBounds()
        updateScrollbarThumb()
    }

    private fun updateScrollbarThumb() {
        val visibleHeight = listBounds.height()
        val contentHeight = entries.size * ITEM_HEIGHT

        if (contentHeight <= visibleHeight) {
            // No scrollbar needed
            scrollbarThumbBounds.setEmpty()
            return
        }

        val thumbHeight = (visibleHeight / contentHeight * visibleHeight).coerceAtLeast(30f)
        val scrollRange = scrollbarBounds.height() - thumbHeight
        val thumbTop = scrollbarBounds.top + (scrollOffset / maxScrollOffset * scrollRange)

        scrollbarThumbBounds.set(
            scrollbarBounds.left,
            thumbTop,
            scrollbarBounds.right,
            thumbTop + thumbHeight
        )
    }

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }

        // Background
        canvas.drawRect(widgetBounds, backgroundPaint)

        // Header
        canvas.drawRect(headerBounds, headerPaint)

        // Navigation buttons
        drawNavButton(canvas, backButtonBounds, isHoveringBack, "back")
        drawNavButton(canvas, homeButtonBounds, isHoveringHome, "home")

        // Path text
        val displayPath = getDisplayPath()
        canvas.drawText(
            displayPath,
            pathTextBounds.left,
            pathTextBounds.centerY() + 5f,
            pathTextPaint
        )

        // List background
        canvas.drawRect(listBounds, listBackgroundPaint)

        // Save canvas state for clipping
        canvas.save()
        canvas.clipRect(listBounds)

        // Draw file items or empty message
        itemBounds.clear()

        if (entries.isEmpty()) {
            // Show empty message with appropriate reason
            val emptyText = when {
                !hasStoragePermission() -> "Storage permission required"
                !currentDirectory.canRead() -> "Cannot read directory"
                else -> "No files found"
            }
            val textWidth = textPaint.measureText(emptyText)
            canvas.drawText(
                emptyText,
                listBounds.centerX() - textWidth / 2,
                listBounds.centerY(),
                pathTextPaint
            )
        }

        var y = listBounds.top - scrollOffset

        for ((index, entry) in entries.withIndex()) {
            val itemTop = y
            val itemBottom = y + ITEM_HEIGHT

            if (itemBottom > listBounds.top && itemTop < listBounds.bottom) {
                val itemRect = RectF(listBounds.left, itemTop, listBounds.right, itemBottom)
                itemBounds.add(itemRect)

                // Item background
                val paint = when {
                    index == hoveredItemIndex -> hoverItemPaint
                    else -> itemPaint
                }
                canvas.drawRect(itemRect, paint)

                // Icon
                val iconLeft = listBounds.left + PADDING
                val iconTop = itemTop + (ITEM_HEIGHT - ICON_SIZE) / 2
                val iconRect = RectF(iconLeft, iconTop, iconLeft + ICON_SIZE, iconTop + ICON_SIZE)
                drawFileIcon(canvas, iconRect, entry)

                // File name
                val textX = iconRect.right + PADDING
                val textY = itemTop + ITEM_HEIGHT / 2 + 6f
                val maxTextWidth = listBounds.right - textX - PADDING
                val displayName = truncateText(entry.name, maxTextWidth, textPaint)
                canvas.drawText(displayName, textX, textY, textPaint)
            } else {
                itemBounds.add(RectF())  // Placeholder for off-screen items
            }

            y += ITEM_HEIGHT
        }

        canvas.restore()

        // Scrollbar
        if (!scrollbarThumbBounds.isEmpty) {
            canvas.drawRect(scrollbarBounds, scrollbarTrackPaint)
            canvas.drawRoundRect(scrollbarThumbBounds, 4f, 4f, scrollbarThumbPaint)
        }

        // Border when hovered
        if (shouldShowBorder()) {
            canvas.drawRect(widgetBounds, hoverBorderPaint)
            drawResizeHandle(canvas)

            if (shouldShowBorderButtons()) {
                drawBorderButtons(canvas)
            }
        }
    }

    private fun drawNavButton(canvas: Canvas, bounds: RectF, isHovering: Boolean, type: String) {
        val paint = if (isHovering) navButtonHoverPaint else navButtonPaint
        canvas.drawRoundRect(bounds, 4f, 4f, paint)

        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val size = 10f

        iconPaint.style = Paint.Style.STROKE

        when (type) {
            "back" -> {
                // Arrow pointing left
                val path = Path().apply {
                    moveTo(cx + size / 2, cy - size)
                    lineTo(cx - size / 2, cy)
                    lineTo(cx + size / 2, cy + size)
                }
                canvas.drawPath(path, iconPaint)
            }
            "home" -> {
                // House icon
                val path = Path().apply {
                    // Roof
                    moveTo(cx - size, cy)
                    lineTo(cx, cy - size)
                    lineTo(cx + size, cy)
                    // Walls
                    moveTo(cx - size * 0.7f, cy)
                    lineTo(cx - size * 0.7f, cy + size)
                    lineTo(cx + size * 0.7f, cy + size)
                    lineTo(cx + size * 0.7f, cy)
                }
                canvas.drawPath(path, iconPaint)
            }
        }
    }

    private fun drawFileIcon(canvas: Canvas, bounds: RectF, entry: FileEntry) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val size = bounds.width() / 2 - 2

        if (entry.isDirectory) {
            // Folder icon
            val path = Path().apply {
                moveTo(cx - size, cy - size * 0.6f)
                lineTo(cx - size * 0.3f, cy - size * 0.6f)
                lineTo(cx - size * 0.1f, cy - size)
                lineTo(cx + size, cy - size)
                lineTo(cx + size, cy + size)
                lineTo(cx - size, cy + size)
                close()
            }
            canvas.drawPath(path, folderIconPaint)
        } else if (fileFilter == FileFilter.IMAGES ||
                   fileFilter.extensions.contains(entry.extension)) {
            // Image icon (mountain/sun)
            val paint = imageIconPaint

            // Frame
            canvas.drawRoundRect(
                cx - size, cy - size * 0.8f,
                cx + size, cy + size * 0.8f,
                3f, 3f, paint
            )

            // Sun (small circle)
            iconPaint.style = Paint.Style.FILL
            iconPaint.color = Color.parseColor("#FFF176")
            canvas.drawCircle(cx + size * 0.4f, cy - size * 0.3f, size * 0.25f, iconPaint)

            // Mountain
            iconPaint.color = Color.parseColor("#81C784")
            val mountainPath = Path().apply {
                moveTo(cx - size * 0.8f, cy + size * 0.6f)
                lineTo(cx - size * 0.2f, cy - size * 0.1f)
                lineTo(cx + size * 0.3f, cy + size * 0.3f)
                lineTo(cx + size * 0.8f, cy - size * 0.3f)
                lineTo(cx + size * 0.8f, cy + size * 0.6f)
                close()
            }
            canvas.drawPath(mountainPath, iconPaint)

            // Reset icon paint
            iconPaint.style = Paint.Style.STROKE
            iconPaint.color = Color.WHITE
        } else {
            // Generic file icon
            val path = Path().apply {
                moveTo(cx - size * 0.6f, cy - size)
                lineTo(cx + size * 0.2f, cy - size)
                lineTo(cx + size * 0.6f, cy - size * 0.6f)
                lineTo(cx + size * 0.6f, cy + size)
                lineTo(cx - size * 0.6f, cy + size)
                close()
                // Folded corner
                moveTo(cx + size * 0.2f, cy - size)
                lineTo(cx + size * 0.2f, cy - size * 0.6f)
                lineTo(cx + size * 0.6f, cy - size * 0.6f)
            }
            canvas.drawPath(path, fileIconPaint)
        }
    }

    private fun getDisplayPath(): String {
        val path = currentDirectory.absolutePath
        val maxLen = 35
        return if (path.length > maxLen) {
            "..." + path.takeLast(maxLen - 3)
        } else {
            path
        }
    }

    private fun truncateText(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text

        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "...") > maxWidth) {
            end--
        }
        return if (end > 0) text.substring(0, end) + "..." else "..."
    }

    fun hitTest(px: Float, py: Float): HitArea {
        if (isMinimized) {
            return if (widgetBounds.contains(px, py)) HitArea.CONTENT else HitArea.NONE
        }

        // Check base widget buttons first
        val baseResult = baseHitTest(px, py)
        when (baseResult) {
            BaseHitArea.CLOSE_BUTTON -> return HitArea.CLOSE_BUTTON
            BaseHitArea.FULLSCREEN_BUTTON -> return HitArea.FULLSCREEN_BUTTON
            BaseHitArea.MINIMIZE_BUTTON -> return HitArea.MINIMIZE_BUTTON
            BaseHitArea.PIN_BUTTON -> return HitArea.PIN_BUTTON
            BaseHitArea.RESIZE_HANDLE -> return HitArea.RESIZE_HANDLE
            else -> {}
        }

        // Navigation buttons
        if (backButtonBounds.contains(px, py)) return HitArea.NAV_BACK
        if (homeButtonBounds.contains(px, py)) return HitArea.NAV_HOME

        // Scrollbar
        if (scrollbarBounds.contains(px, py)) return HitArea.SCROLLBAR

        // File items
        if (listBounds.contains(px, py)) {
            val relativeY = py - listBounds.top + scrollOffset
            val index = (relativeY / ITEM_HEIGHT).toInt()
            if (index in entries.indices) {
                return HitArea.FILE_ITEM
            }
            return HitArea.CONTENT
        }

        // Header
        if (headerBounds.contains(px, py)) return HitArea.CONTENT

        // Border
        val expandedBounds = RectF(
            widgetBounds.left - BORDER_HIT_AREA,
            widgetBounds.top - BORDER_HIT_AREA,
            widgetBounds.right + BORDER_HIT_AREA,
            widgetBounds.bottom + BORDER_HIT_AREA
        )
        if (expandedBounds.contains(px, py)) return HitArea.BORDER

        return HitArea.NONE
    }

    override fun updateHover(px: Float, py: Float) {
        if (state == State.MOVING || state == State.RESIZING) return

        val baseResult = updateHoverState(px, py)

        // Reset hover states
        val oldHoveredIndex = hoveredItemIndex
        hoveredItemIndex = -1
        isHoveringBack = false
        isHoveringHome = false
        isHoveringScrollbar = false

        if (!isMinimized) {
            // Check nav buttons
            isHoveringBack = backButtonBounds.contains(px, py)
            isHoveringHome = homeButtonBounds.contains(px, py)
            isHoveringScrollbar = scrollbarBounds.contains(px, py)

            // Check file items
            if (listBounds.contains(px, py)) {
                val relativeY = py - listBounds.top + scrollOffset
                val index = (relativeY / ITEM_HEIGHT).toInt()
                if (index in entries.indices) {
                    hoveredItemIndex = index
                }
            }
        }

        val newState = when (baseResult) {
            BaseState.HOVER_RESIZE -> State.HOVER_BORDER
            BaseState.HOVER_BORDER -> State.HOVER_BORDER
            BaseState.HOVER_CONTENT -> State.HOVER_CONTENT
            else -> State.IDLE
        }

        if (newState != state || hoveredItemIndex != oldHoveredIndex) {
            state = newState
        }
    }

    fun onTap(px: Float, py: Float): Boolean {
        if (isMinimized) {
            toggleMinimize()
            return true
        }

        val hitArea = hitTest(px, py)

        return when (hitArea) {
            HitArea.CLOSE_BUTTON -> {
                onCloseRequested?.invoke()
                true
            }
            HitArea.FULLSCREEN_BUTTON -> {
                toggleFullscreen()
                true
            }
            HitArea.MINIMIZE_BUTTON -> {
                toggleMinimize()
                true
            }
            HitArea.PIN_BUTTON -> {
                isPinned = !isPinned
                true
            }
            HitArea.NAV_BACK -> {
                navigateUp()
                true
            }
            HitArea.NAV_HOME -> {
                navigateHome()
                true
            }
            HitArea.FILE_ITEM -> {
                val relativeY = py - listBounds.top + scrollOffset
                val index = (relativeY / ITEM_HEIGHT).toInt()
                if (index in entries.indices) {
                    val entry = entries[index]
                    if (entry.isDirectory) {
                        // Navigate into directory - use file if available, otherwise create from path
                        val dirFile = entry.file ?: File(entry.absolutePath)
                        loadDirectory(dirFile)
                    } else {
                        // Select the file
                        onFileSelected?.invoke(entry)
                    }
                }
                true
            }
            HitArea.RESIZE_HANDLE, HitArea.BORDER -> {
                // Prepare for drag
                true
            }
            else -> false
        }
    }

    override fun onScroll(dy: Float) {
        if (maxScrollOffset <= 0) return

        scrollOffset = (scrollOffset + dy).coerceIn(0f, maxScrollOffset)
        updateScrollbarThumb()
        onStateChanged?.invoke(state)
    }

    fun startMove() {
        state = State.MOVING
        baseState = BaseState.MOVING
    }

    fun startResize() {
        state = State.RESIZING
        baseState = BaseState.RESIZING
    }

    fun endDrag() {
        state = State.IDLE
        baseState = BaseState.IDLE
    }

    override fun onDrag(dx: Float, dy: Float, screenWidth: Float, screenHeight: Float) {
        super.onDrag(dx, dy, screenWidth, screenHeight)

        if (baseState == BaseState.RESIZING) {
            updateBaseBounds()
        }
    }

    private fun navigateUp() {
        val parent = currentDirectory.parentFile
        if (parent != null && parent.canRead()) {
            loadDirectory(parent)
        }
    }

    private fun navigateHome() {
        loadDirectory(getDefaultDirectory())
    }

    /**
     * Navigate to a specific directory
     */
    fun navigateTo(path: String) {
        val dir = File(path)
        if (dir.exists() && dir.isDirectory && dir.canRead()) {
            loadDirectory(dir)
        }
    }

    /**
     * Change the file filter and reload
     */
    fun setFilter(filter: FileFilter) {
        if (filter != fileFilter) {
            loadDirectory(currentDirectory)
        }
    }
}
