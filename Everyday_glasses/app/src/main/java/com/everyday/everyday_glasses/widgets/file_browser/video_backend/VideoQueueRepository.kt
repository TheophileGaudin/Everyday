package com.everyday.everyday_glasses.widgets.file_browser.video_backend

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.everyday.everyday_glasses.widgets.file_browser.FileBrowserWidget
import java.io.File
import java.util.Locale

/** Discovers the playable video files beside a selected local video. */
internal class VideoQueueRepository(private val context: Context) {

    /**
     * Build the player's initial queue before the widget is created.
     *
     * If the selected URI cannot be mapped to a readable directory, the selected video is still
     * returned by itself. This preserves the existing fallback instead of failing widget creation.
     */
    fun buildQueue(initialUri: Uri, initialDisplayName: String): List<VideoItem> {
        val initial = VideoItem(
            id = initialUri.hashCode().toLong(),
            contentUri = initialUri,
            displayName = initialDisplayName,
        )
        val initialPath = resolveVideoPath(initialUri) ?: return listOf(initial)
        val parent = File(initialPath).parentFile ?: return listOf(initial)
        val initialCanonicalPath = File(initialPath).canonicalPath
        val files = sortedVideoFiles(parent)

        if (files.isEmpty()) return listOf(initial)

        return files.map { file ->
            val canonicalPath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            val fileUri = if (canonicalPath == initialCanonicalPath) {
                initialUri
            } else {
                queryVideoUriForPath(file.absolutePath) ?: Uri.fromFile(file)
            }
            VideoItem(
                id = fileUri.hashCode().toLong(),
                contentUri = fileUri,
                displayName = file.name,
            )
        }.ifEmpty { listOf(initial) }
    }

    private fun resolveVideoPath(uri: Uri): String? {
        if (uri.scheme.equals("file", ignoreCase = true)) return uri.path
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Video.Media.DATA),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA))
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun queryVideoUriForPath(path: String): Uri? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DATA} = ?",
                arrayOf(path),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else ContentUris.withAppendedId(
                    collection,
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)),
                )
            }
        }.getOrNull()
    }
}

/** Pure file filtering kept outside Android APIs so its ordering rule is easy to unit-test. */
internal fun sortedVideoFiles(parent: File): List<File> =
    parent.listFiles()
        ?.asSequence()
        ?.filter {
            it.isFile &&
                    it.extension.lowercase(Locale.US) in FileBrowserWidget.FileFilter.VIDEOS.extensions
        }
        ?.sortedBy { it.name.lowercase(Locale.US) }
        ?.toList()
        .orEmpty()