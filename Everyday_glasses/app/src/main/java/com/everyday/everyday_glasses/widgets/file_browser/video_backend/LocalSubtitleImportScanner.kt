package com.everyday.everyday_glasses.widgets.file_browser.video_backend

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalSubtitleCandidate(
    val displayName: String,
    val contentUri: Uri,
    val modifiedAtMs: Long,
    val relativePath: String?,
)

class LocalSubtitleImportScanner(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun findCandidatesFor(videoItem: VideoItem): List<LocalSubtitleCandidate> = withContext(ioDispatcher) {
        val normalizedVideoStem = videoItem.displayName
            .substringBeforeLast('.', videoItem.displayName)
            .lowercase(Locale.US)

        val rankedRows = querySubtitleRows(normalizedVideoStem)
            .asSequence()
            .filter { row ->
                isSupportedSubtitleFileName(row.displayName) ||
                    resolveSupportedSubtitleMimeType(row.mimeType, row.displayName) != null
            }
            .filter { row -> isAllowedImportPath(row.relativePath) }
            .distinctBy { row -> row.fileIdentityKey() }
            .map { row -> row to subtitleMatchRank(normalizedVideoStem, row.displayName) }
            .sortedWith(
                compareBy<Pair<SubtitleMediaRow, Int>> { it.second }
                    .thenByDescending { it.first.modifiedAtMs }
                    .thenBy { it.first.displayName.lowercase(Locale.US) }
            )
            .toList()

        // Plain .txt is accepted because some subtitle downloads use that extension, but the
        // glasses also contain thousands of unrelated diagnostic .txt files. Only inspect text
        // files whose name matches the current video; real subtitle extensions are always kept.
        val candidates = rankedRows
            .asSequence()
            .filter { (row, rank) ->
                subtitleExtension(row.displayName) != "txt" || rank < UNMATCHED_RANK
            }
            .filter { (row, _) -> isUsableSubtitleCandidate(row) }
            .map { (row, rank) ->
                LocalSubtitleCandidate(
                    displayName = row.displayName,
                    contentUri = row.contentUri,
                    modifiedAtMs = row.modifiedAtMs,
                    relativePath = row.relativePath,
                ) to rank
            }
            .map { it.first }
            .take(MAX_RESULTS)
            .toList()
        Log.d(TAG, "Returning ${candidates.size} usable subtitle candidates for ${videoItem.displayName}")
        candidates
    }

    private fun querySubtitleRows(normalizedVideoStem: String): List<SubtitleMediaRow> {
        // Prefer the direct scan when all-files access is available. MediaStore can retain a
        // stale row for a moved/deleted subtitle, and some RayNeo builds do not index .srt
        // files at all. The direct scan is what makes a freshly copied Download/foo.srt show
        // up immediately, while MediaStore remains the scoped-storage fallback.
        val fileSystemRows = runCatching { queryFileSystemSubtitleRows() }
            .onFailure { error ->
                Log.w(TAG, "Filesystem subtitle scan failed", error)
            }
            .getOrDefault(emptyList())
        val mediaStoreRows = runCatching { queryMediaStoreSubtitleRows(normalizedVideoStem) }
            .onFailure { error ->
                Log.w(TAG, "MediaStore subtitle query failed; using filesystem scan", error)
            }
            .getOrDefault(emptyList())

        Log.d(TAG, "Subtitle candidates: filesystem=${fileSystemRows.size}, mediaStore=${mediaStoreRows.size}")
        return (fileSystemRows + mediaStoreRows)
            .distinctBy { row -> row.fileIdentityKey() }
    }

    private fun queryMediaStoreSubtitleRows(normalizedVideoStem: String): List<SubtitleMediaRow> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME)
            add(MediaStore.Files.FileColumns.DATE_MODIFIED)
            add(MediaStore.Files.FileColumns.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Files.FileColumns.RELATIVE_PATH)
            } else {
                add(MediaStore.Files.FileColumns.DATA)
            }
        }.toTypedArray()

        val results = mutableListOf<SubtitleMediaRow>()
        val extensionPatterns = MEDIASTORE_SUBTITLE_EXTENSIONS.map { extension -> "%.$extension" }
        val selectionParts = extensionPatterns.map { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?" }
            .toMutableList()
        val selectionArgs = extensionPatterns.toMutableList()
        // Keep timed .txt support without fetching every diagnostic text log on the glasses.
        selectionParts += "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        selectionArgs += "$normalizedVideoStem%.txt"

        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Files.FileColumns.SIZE} > 0 AND (${selectionParts.joinToString(" OR ")})",
            selectionArgs.toTypedArray(),
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dateModifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val mimeTypeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val dataIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val relativePathIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                results += SubtitleMediaRow(
                    displayName = cursor.getString(nameIndex).orEmpty(),
                    contentUri = ContentUris.withAppendedId(collection, id),
                    modifiedAtMs = cursor.getLong(dateModifiedIndex).coerceAtLeast(0L) * 1000L,
                    mimeType = if (cursor.isNull(mimeTypeIndex)) null else cursor.getString(mimeTypeIndex),
                    relativePath = relativePathIndex.takeIf { it >= 0 }?.let { index ->
                        if (cursor.isNull(index)) null else cursor.getString(index)
                    }?.takeIf(::isRecognizedImportPath)
                        ?: dataIndex.takeIf { it >= 0 }?.let { index ->
                            if (cursor.isNull(index)) null else relativePathFromAbsolutePath(cursor.getString(index))
                        },
                )
            }
        }
        return results
    }

    private fun queryFileSystemSubtitleRows(): List<SubtitleMediaRow> {
        val standardDirectories = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            // A few vendor file managers use the plural spelling even though Android's
            // DIRECTORY_DOWNLOADS constant resolves to the singular "Download" directory.
            File(Environment.getExternalStorageDirectory(), "Downloads"),
        )
            .distinctBy { file -> runCatching { file.canonicalPath }.getOrDefault(file.absolutePath) }
            .filter { it.exists() && it.isDirectory }
        if (standardDirectories.isEmpty()) {
            return emptyList()
        }

        val externalStorageRoot = Environment.getExternalStorageDirectory()
        return standardDirectories
            .asSequence()
            .flatMap { directory ->
                directory.walkTopDown()
                    .onFail { _, _ -> }
                    .maxDepth(MAX_SCAN_DEPTH)
                    .filter { file -> file.isFile && isSupportedSubtitleFileName(file.name) }
                    .map { file ->
                        SubtitleMediaRow(
                            displayName = file.name,
                            contentUri = Uri.fromFile(file),
                            modifiedAtMs = file.lastModified().coerceAtLeast(0L),
                            mimeType = resolveSupportedSubtitleMimeType(null, file.name),
                            relativePath = resolveRelativePath(file, externalStorageRoot),
                        )
                    }
            }
            .toList()
    }

    private fun resolveRelativePath(file: File, externalStorageRoot: File): String? {
        val parent = file.parentFile ?: return null
        val rootPath = externalStorageRoot.absolutePath.trimEnd(File.separatorChar)
        val parentPath = parent.absolutePath
        if (!parentPath.startsWith(rootPath)) return null
        val normalizedPath = parentPath
            .removePrefix(rootPath)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
        return if (normalizedPath.isBlank()) null else "$normalizedPath/"
    }

    private fun subtitleMatchRank(normalizedVideoStem: String, subtitleName: String): Int {
        val normalizedSubtitleStem = subtitleName
            .substringBeforeLast('.', subtitleName)
            .lowercase(Locale.US)

        return when {
            normalizedSubtitleStem == normalizedVideoStem -> 0
            normalizedSubtitleStem.startsWith("$normalizedVideoStem.") -> 1
            normalizedSubtitleStem.startsWith("${normalizedVideoStem}_") -> 1
            normalizedSubtitleStem.startsWith("${normalizedVideoStem}-") -> 1
            normalizedSubtitleStem.contains(normalizedVideoStem) -> 2
            else -> 3
        }
    }

    private fun isUsableSubtitleCandidate(row: SubtitleMediaRow): Boolean {
        if (subtitleExtension(row.displayName) != "txt") return true
        val probeBytes = runCatching {
            context.contentResolver.openInputStream(row.contentUri)?.use { inputStream ->
                val buffer = ByteArray(MAX_TEXT_PROBE_BYTES)
                val byteCount = inputStream.read(buffer)
                if (byteCount <= 0) ByteArray(0) else buffer.copyOf(byteCount)
            }
        }.getOrNull() ?: return false
        if (probeBytes.isEmpty()) return false

        val probeText = probeBytes.toString(Charsets.ISO_8859_1)
        return runCatching {
            normalizeTextSubtitleForPlayback(SubtitleImportType.TEXT_AUTO, probeText) != null
        }.getOrDefault(false)
    }

    private fun isAllowedImportPath(relativePath: String?): Boolean {
        if (relativePath.isNullOrBlank()) return false
        val normalized = relativePath
            .lowercase(Locale.US)
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .removePrefix("primary:")
            .trimStart('/')
        return normalized == "documents" || normalized.startsWith("documents/") ||
            normalized == "document" || normalized.startsWith("document/") ||
            normalized == "download" || normalized.startsWith("download/") ||
            normalized == "downloads" || normalized.startsWith("downloads/") ||
            normalized == "movies" || normalized.startsWith("movies/")
    }

    private fun isRecognizedImportPath(relativePath: String?): Boolean {
        return relativePath != null && isAllowedImportPath(relativePath)
    }

    private fun relativePathFromAbsolutePath(absolutePath: String?): String? {
        if (absolutePath.isNullOrBlank()) return null
        val normalizedRootPath = Environment.getExternalStorageDirectory().absolutePath
            .replace('\\', '/')
            .trimEnd('/')
        val normalizedAbsolutePath = absolutePath.replace('\\', '/')
        if (!normalizedAbsolutePath.startsWith("$normalizedRootPath/", ignoreCase = true)) {
            return null
        }
        val parentPath = normalizedAbsolutePath.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentPath.isBlank()) return null
        return parentPath
            .removePrefix(normalizedRootPath)
            .trimStart('/')
            .plus('/')
    }

    private fun SubtitleMediaRow.fileIdentityKey(): String {
        val normalizedRelativePath = relativePath
            ?.replace('\\', '/')
            ?.trim()
            ?.trimStart('/')
            ?.trimEnd('/')
            ?.lowercase(Locale.US)
            .orEmpty()
        val normalizedName = displayName.lowercase(Locale.US)
        return if (normalizedRelativePath.isNotBlank()) {
            "$normalizedRelativePath/$normalizedName"
        } else {
            contentUri.toString()
        }
    }

    private data class SubtitleMediaRow(
        val displayName: String,
        val contentUri: Uri,
        val modifiedAtMs: Long,
        val mimeType: String?,
        val relativePath: String?,
    )

    companion object {
        private const val TAG = "LocalSubtitleScanner"
        private const val MAX_RESULTS = 150
        private const val MAX_SCAN_DEPTH = 5
        private const val MAX_TEXT_PROBE_BYTES = 64 * 1024
        private const val UNMATCHED_RANK = 3
        private val MEDIASTORE_SUBTITLE_EXTENSIONS = listOf(
            "srt", "sub", "vtt", "vvt", "ssa", "ass", "ttml", "dfxp", "xml"
        )
    }
}
