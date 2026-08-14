package com.everyday.everyday_glasses

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Owns the app-managed image files that back persisted image widgets.
 */
object ImageWidgetStorage {
    private const val TAG = "ImageWidgetStorage"
    private const val MANAGED_DIR_NAME = "widget_images"

    fun createManagedFile(context: Context, fileName: String): File {
        return File(getManagedDir(context), fileName)
    }

    fun isManagedPath(context: Context, path: String): Boolean {
        if (path.isBlank()) return false

        return runCatching {
            val managedDir = getManagedDir(context).canonicalFile
            val file = File(path).canonicalFile
            file == managedDir || file.path.startsWith(managedDir.path + File.separator)
        }.getOrElse { false }
    }

    fun deleteIfManaged(context: Context, path: String): Boolean {
        if (!isManagedPath(context, path)) return false

        val file = File(path)
        if (!file.exists()) return false

        val deleted = runCatching { file.delete() }.getOrDefault(false)
        if (!deleted) {
            Log.w(TAG, "Failed to delete managed image file: $path")
        }
        return deleted
    }

    fun pruneUnreferenced(context: Context, referencedPaths: Collection<String>) {
        val managedDir = getManagedDir(context)
        val keep = referencedPaths.mapNotNull { path ->
            if (!isManagedPath(context, path)) {
                null
            } else {
                runCatching { File(path).canonicalFile.absolutePath }.getOrNull()
            }
        }.toSet()

        managedDir.listFiles()?.forEach { file ->
            val canonicalPath = runCatching { file.canonicalFile.absolutePath }.getOrNull() ?: return@forEach
            if (canonicalPath !in keep && file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to prune managed image file: $canonicalPath")
            }
        }
    }

    private fun getManagedDir(context: Context): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        return File(baseDir, MANAGED_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
}
