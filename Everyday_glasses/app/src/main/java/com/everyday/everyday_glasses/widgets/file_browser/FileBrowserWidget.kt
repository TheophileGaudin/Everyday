package com.everyday.everyday_glasses.widgets.file_browser

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
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.FileBrowserWidgetListener
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.persistence.DashboardPersistence.parseSizedWidgetObject
import com.everyday.everyday_glasses.persistence.DashboardPersistence.putCommonFields
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.widgets._common.WidgetInteractionState
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import java.io.File
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withClip
import com.everyday.everyday_glasses.runtime.WidgetDescriptor
import com.everyday.everyday_glasses.runtime.WidgetInstancePolicy
import com.everyday.everyday_glasses.runtime.WidgetType
import org.json.JSONObject

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
    val fileFilter: FileFilter = FileFilter.IMAGES
) : BaseWidget(FileBrowserPlugin.TYPE, x, y, width, height) {

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
    enum class FileFilter(val extensions: Set<String>) {
        IMAGES(setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")),
        MEDIA(setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "mp4", "mkv", "webm", "mov", "avi", "m4v", "ts", "pdf")),
        VIDEOS(setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "ts")),
        PDF(setOf("pdf")),
        ALL(emptySet())
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
        val absolutePath: String,  // Full path for display and file operations
        val mimeType: String? = null
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
    var listener: FileBrowserWidgetListener? = null

    // Current directory and file list
    var currentDirectory: File = getDefaultDirectory()
        private set
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
        color = "#1a1a2e".toColorInt()
        style = Paint.Style.FILL
    }

    private val listBackgroundPaint = Paint().apply {
        color = "#0d0d1a".toColorInt()
        style = Paint.Style.FILL
    }

    private val itemPaint = Paint().apply {
        color = "#1a1a2e".toColorInt()
        style = Paint.Style.FILL
    }

    private val hoverItemPaint = Paint().apply {
        color = "#3a3a5e".toColorInt()
        style = Paint.Style.FILL
    }


    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 18f
        isAntiAlias = true
    }

    private val pathTextPaint = Paint().apply {
        color = "#AAAAAA".toColorInt()
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
        color = "#FFD54F".toColorInt()  // Yellow for folders
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val imageIconPaint = Paint().apply {
        color = "#4FC3F7".toColorInt()  // Light blue for images
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val fileIconPaint = Paint().apply {
        color = "#90A4AE".toColorInt()  // Gray for generic files
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val scrollbarTrackPaint = Paint().apply {
        color = "#333344".toColorInt()
        style = Paint.Style.FILL
    }

    private val scrollbarThumbPaint = Paint().apply {
        color = "#6666AA".toColorInt()
        style = Paint.Style.FILL
    }

    private val navButtonPaint = Paint().apply {
        color = "#3a3a5e".toColorInt()
        style = Paint.Style.FILL
    }

    private val navButtonHoverPaint = Paint().apply {
        color = "#5050AA".toColorInt()
        style = Paint.Style.FILL
    }

    override val minWidth = MIN_WIDGET_SIZE
    override val minHeight = MIN_WIDGET_SIZE
    override val minimizeLabel = "F"

    init {
        updateBaseBounds()
        loadDirectory(currentDirectory)
    }

    private fun getDefaultDirectory(): File {
        if (fileFilter == FileFilter.VIDEOS) {
            val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (movies.exists() && movies.canRead()) return movies
        }
        if (fileFilter == FileFilter.MEDIA) {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloads.exists() && downloads.canRead()) return downloads
        }
        // Try common media directories
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
        return context.filesDir
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

            val hasDirectSharedStorageAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()

            // All-files access is already required by Everyday's general browser. In that mode
            // listFiles() is both complete and much faster than repeating several MediaStore
            // queries on every directory tap.
            if (hasDirectSharedStorageAccess) {
                files.orEmpty()
                    .filter { file ->
                        !file.isDirectory && when (fileFilter) {
                            FileFilter.ALL -> true
                            else -> file.extension.lowercase() in fileFilter.extensions
                        }
                    }
                    .forEach { file ->
                        resultEntries += FileEntry(
                            file = file,
                            uri = Uri.fromFile(file),
                            name = file.name,
                            isDirectory = false,
                            extension = file.extension.lowercase(),
                            absolutePath = file.absolutePath,
                        )
                    }
            } else if (fileFilter in setOf(FileFilter.IMAGES, FileFilter.VIDEOS, FileFilter.PDF, FileFilter.MEDIA)) {
                // Scoped-storage fallback when broad filesystem access is unavailable.
                val mediaFiles = buildList {
                    if (fileFilter == FileFilter.IMAGES || fileFilter == FileFilter.MEDIA) addAll(queryMediaStoreForImages(directory.absolutePath))
                    if (fileFilter == FileFilter.VIDEOS || fileFilter == FileFilter.MEDIA) addAll(queryMediaStoreForVideos(directory.absolutePath))
                    if (fileFilter == FileFilter.PDF || fileFilter == FileFilter.MEDIA) addAll(queryMediaStoreForPdfs(directory.absolutePath))
                }
                val directPdfs = if (fileFilter == FileFilter.PDF || fileFilter == FileFilter.MEDIA) {
                    files.orEmpty().filter { !it.isDirectory && it.extension.equals("pdf", ignoreCase = true) }.map { file ->
                        FileEntry(file, Uri.fromFile(file), file.name, false, "pdf", file.absolutePath, "application/pdf")
                    }
                } else emptyList()
                resultEntries.addAll((mediaFiles + directPdfs).distinctBy { it.absolutePath })
                Log.d(TAG, "MediaStore returned ${mediaFiles.size} files for ${directory.absolutePath}")
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
        notifyTransientChange()
    }

    /**
     * Check if the app has storage permissions to read files.
     */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val imagesGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            val videosGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            when (fileFilter) {
                FileFilter.IMAGES -> imagesGranted
                FileFilter.VIDEOS -> videosGranted
                FileFilter.MEDIA -> imagesGranted && videosGranted
                else -> true
            }
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

            // Query only this directory; avoid a full-device diagnostic query in this hot path.
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

    private fun queryMediaStoreForVideos(directoryPath: String): List<FileEntry> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val videos = mutableListOf<FileEntry>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.MIME_TYPE,
        )
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.Video.Media.DATA} LIKE ?",
                arrayOf("$directoryPath/%"),
                "${MediaStore.Video.Media.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataColumn) ?: continue
                    if (File(path).parent != directoryPath) continue
                    val name = cursor.getString(nameColumn).orEmpty()
                    videos += FileEntry(
                        file = null,
                        uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                        name = name,
                        isDirectory = false,
                        extension = name.substringAfterLast('.', "").lowercase(),
                        absolutePath = path,
                        mimeType = cursor.getString(mimeColumn),
                    )
                }
            }
        }.onFailure { Log.e(TAG, "Error querying videos in $directoryPath", it) }
        return videos
    }

    private fun queryMediaStoreForPdfs(directoryPath: String): List<FileEntry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val documents = mutableListOf<FileEntry>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.RELATIVE_PATH,
        )
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val relativeDirectory = directoryPath
            .removePrefix(storageRoot)
            .trim('/')
            .let { if (it.isBlank()) "" else "$it/" }
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.Downloads.MIME_TYPE} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf("application/pdf", relativeDirectory),
                "${MediaStore.Downloads.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                    val name = cursor.getString(nameColumn).orEmpty()
                    val path = File(directoryPath, name).absolutePath
                    documents += FileEntry(null, uri, name, false, "pdf", path, "application/pdf")
                }
            }
            Log.d(TAG, "MediaStore PDF query returned ${documents.size} rows for $relativeDirectory")
        }.onFailure { Log.w(TAG, "Unable to query PDFs in $directoryPath", it) }
        return documents
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
        canvas.withClip(listBounds) {
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
                drawText(
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
                    drawRect(itemRect, paint)

                    // Icon
                    val iconLeft = listBounds.left + PADDING
                    val iconTop = itemTop + (ITEM_HEIGHT - ICON_SIZE) / 2
                    val iconRect =
                        RectF(iconLeft, iconTop, iconLeft + ICON_SIZE, iconTop + ICON_SIZE)
                    drawFileIcon(this, iconRect, entry)

                    // File name
                    val textX = iconRect.right + PADDING
                    val textY = itemTop + ITEM_HEIGHT / 2 + 6f
                    val maxTextWidth = listBounds.right - textX - PADDING
                    val displayName = truncateText(entry.name, maxTextWidth, textPaint)
                    drawText(displayName, textX, textY, textPaint)
                } else {
                    itemBounds.add(RectF())  // Placeholder for off-screen items
                }

                y += ITEM_HEIGHT
            }

        }

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
        } else if (entry.extension in FileFilter.IMAGES.extensions) {
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
            iconPaint.color = "#FFF176".toColorInt()
            canvas.drawCircle(cx + size * 0.4f, cy - size * 0.3f, size * 0.25f, iconPaint)

            // Mountain
            iconPaint.color = "#81C784".toColorInt()
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
        } else if (entry.extension in FileFilter.VIDEOS.extensions) {
            canvas.drawRoundRect(cx - size, cy - size * 0.8f, cx + size, cy + size * 0.8f, 3f, 3f, fileIconPaint)
            val playPath = Path().apply {
                moveTo(cx - size * 0.25f, cy - size * 0.45f)
                lineTo(cx + size * 0.5f, cy)
                lineTo(cx - size * 0.25f, cy + size * 0.45f)
                close()
            }
            canvas.drawPath(playPath, imageIconPaint)
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

    /** Only the border moves this widget and only the resize handle resizes it. */
    override fun dragHitTest(px: Float, py: Float): DragHit = when (hitTest(px, py)) {
        HitArea.BORDER -> DragHit.DRAG
        HitArea.RESIZE_HANDLE -> DragHit.RESIZE
        else -> DragHit.NONE
    }

    override fun isBorderHit(px: Float, py: Float): Boolean = hitTest(px, py) == HitArea.BORDER

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

        val newState = WidgetInteractionState(baseResult).toChromeLocal(
            idle = State.IDLE,
            content = State.HOVER_CONTENT,
            border = State.HOVER_BORDER,
            moving = State.IDLE
        )

        if (newState != state || hoveredItemIndex != oldHoveredIndex) {
            state = newState
        }
    }
    override val bringsToFrontOnTap: Boolean get() = true
    override fun onTap(px: Float, py: Float): Boolean {
        if (isMinimized) {
            toggleMinimize()
            return true
        }

        val hitArea = hitTest(px, py)

        return when (hitArea) {
            HitArea.CLOSE_BUTTON -> {
                lifecycleListener?.onCloseRequested(this)
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
                        listener?.onFileSelected(entry)
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
        notifyTransientChange()
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

}
/**
 * Opens the file picker, and brings it back with the saved dashboard. Picking a file hands it to
 * [onPicked] and closes the picker.
 */
class FileBrowserPlugin(
    private val services: WidgetContext,
) : WidgetPlugin {
    private val context: Context get() = services.androidContext

    /** What picking a file does. The file import sets it at startup, so a restored picker imports too. */
    var onPicked: (FileBrowserWidget.FileEntry) -> Unit = {}

    override val lifecycle = WidgetLifecycleRegistration.multiple(
        FileBrowserWidget::class, FileBrowserWidgetState.Codec,
        capture = ::capture,
        restore = ::restore,
        configure = ::configure,
    )

    fun open(filter: FileBrowserWidget.FileFilter) {
        // Half the dashboard's width and 70% of its height, within limits, centered.
        val width = (services.viewportWidth * 0.5f).coerceIn(300f, 500f)
        val height = (services.viewportHeight * 0.7f).coerceIn(300f, 600f)
        val x = ((services.viewportWidth - width) / 2f).coerceAtLeast(0f)
        val y = ((services.viewportHeight - height) / 2f).coerceAtLeast(0f)
        services.open(FileBrowserPlugin.TYPE) { FileBrowserWidget(context, x, y, width, height, filter) }
        // Opening the picker may complete asynchronously after a runtime/all-files permission
        // screen. Re-center after the widget actually exists so hover chrome is visible at once
        // and two-finger scrolling is routed to the new browser without a priming drag.
        services.host?.requestCursorCenter()
    }

    /** For a new picker and a restored one alike: a pick goes to [onPicked], then the picker closes. */
    private fun configure(picker: FileBrowserWidget) {
        picker.listener = object : FileBrowserWidgetListener {
            override fun onFileSelected(entry: FileBrowserWidget.FileEntry) {
                onPicked(entry)
                services.close(picker)
            }
        }
    }

    /** Back in its saved folder; in the default one when that folder can't be read anymore. */
    private fun restore(state: FileBrowserWidgetState): FileBrowserWidget {
        val picker = FileBrowserWidget(context, state.x, state.y, state.width, state.height, state.filter)
        File(state.directory).takeIf { it.isDirectory && it.canRead() }?.let(picker::loadDirectory)
        services.captureRestore.restoreCommonFields(picker, state, services.viewportWidth, services.viewportHeight)
        return picker
    }

    private fun capture(picker: FileBrowserWidget): FileBrowserWidgetState {
        val c = services.captureRestore.extractCommonFields(picker)
        return FileBrowserWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            filter = picker.fileFilter,
            directory = picker.currentDirectory.absolutePath,
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    /** The file picker as a widget type: its id, its descriptor, how it is saved, and its plugin. */
    companion object : WidgetType {
        val TYPE = WidgetTypeId("files")
        override val descriptor = WidgetDescriptor(
            TYPE,
            "Files",
            WidgetInstancePolicy.MULTIPLE,
            order = 15,
            category = "content"
        )
        override val codec get() = FileBrowserWidgetState.Codec
        override fun create(context: WidgetContext) = FileBrowserPlugin(context)
    }
}

/** What a saved dashboard keeps of a file picker: which files it shows, and the folder it's in. */
data class FileBrowserWidgetState(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    val filter: FileBrowserWidget.FileFilter = FileBrowserWidget.FileFilter.MEDIA,
    val directory: String = "",
    override val isMinimized: Boolean = false,
    override val isFullscreen: Boolean = false,
    override val savedMinX: Float = 0f,
    override val savedMinY: Float = 0f,
    override val savedMinWidth: Float = 0f,
    override val savedMinHeight: Float = 0f,
    override val isPinned: Boolean = false
) : DashboardPersistence.SizedWidgetFields {
    /** Its entry in the saved dashboard file. */
    companion object Codec : WidgetDocument.StateCodec<FileBrowserWidgetState>(FileBrowserPlugin.TYPE, FileBrowserWidgetState::class) {
        override fun toJson(state: FileBrowserWidgetState): JSONObject = JSONObject().apply {
            putCommonFields(state)
            put("filter", state.filter.name)
            put("directory", state.directory)
        }

        override fun fromJson(payload: JSONObject): FileBrowserWidgetState? =
            parseSizedWidgetObject(payload) { obj, c, w, h ->
                FileBrowserWidgetState(
                    x = c.x, y = c.y, width = w, height = h,
                    filter = runCatching { FileBrowserWidget.FileFilter.valueOf(obj.optString("filter")) }
                        .getOrDefault(FileBrowserWidget.FileFilter.MEDIA),
                    directory = obj.optString("directory", ""),
                    isMinimized = c.isMinimized,
                    isFullscreen = c.isFullscreen,
                    savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                    savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                    isPinned = c.isPinned
                )
            }
    }
}