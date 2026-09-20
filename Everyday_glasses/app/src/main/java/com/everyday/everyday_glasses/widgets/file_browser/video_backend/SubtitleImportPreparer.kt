package com.everyday.everyday_glasses.widgets.file_browser.video_backend

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class PreparedSubtitleImport(
    val playbackUri: Uri,
    val mimeType: String,
)

class SubtitleImportPreparer(
    private val context: Context,
) {
    suspend fun prepare(
        videoId: Long,
        contentUri: Uri,
        displayName: String,
        rawMimeType: String?,
    ): PreparedSubtitleImport = withContext(Dispatchers.IO) {
        val importType = resolveSupportedSubtitleImportType(rawMimeType, displayName)
            ?: throw IllegalArgumentException("Unsupported subtitle format.")

        val importDirectory = File(context.filesDir, "imported_subtitles/video_$videoId")
        if (!importDirectory.exists() && !importDirectory.mkdirs()) {
            throw IllegalStateException("Could not create subtitle import directory.")
        }

        prepareNormalizedSubtitle(
            importDirectory = importDirectory,
            contentUri = contentUri,
            displayName = displayName,
            importType = importType,
        )
    }

    private fun prepareNormalizedSubtitle(
        importDirectory: File,
        contentUri: Uri,
        displayName: String,
        importType: SubtitleImportType,
    ): PreparedSubtitleImport {
        val sourceBytes = context.contentResolver.openInputStream(contentUri)?.use { inputStream ->
            inputStream.readBytes()
        } ?: throw IllegalArgumentException("Unsupported subtitle format.")

        val sourceText = decodeSubtitleText(sourceBytes)
        val normalizedSubtitle = normalizeTextSubtitleForPlayback(importType, sourceText)
            ?: throw IllegalArgumentException("Unsupported subtitle format.")

        val targetFile = File(
            importDirectory,
            buildStableFileName(
                displayName = displayName,
                contentUri = contentUri,
                fileExtension = normalizedSubtitle.fileExtension,
            )
        )
        targetFile.writeText(normalizedSubtitle.content, Charsets.UTF_8)

        return PreparedSubtitleImport(
            playbackUri = Uri.fromFile(targetFile),
            mimeType = normalizedSubtitle.mimeType,
        )
    }

    private fun buildStableFileName(
        displayName: String,
        contentUri: Uri,
        fileExtension: String,
    ): String {
        val baseName = displayName
            .substringBeforeLast('.', displayName)
            .replace(UNSAFE_FILE_NAME_REGEX, "_")
            .trim('_')
            .ifBlank { "subtitle" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(contentUri.toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
            .take(12)
        return "$baseName-$digest.$fileExtension"
    }

    private fun decodeSubtitleText(sourceBytes: ByteArray): String {
        if (sourceBytes.isEmpty()) return ""
        if (sourceBytes.size >= 3 &&
            sourceBytes[0] == UTF8_BOM[0] &&
            sourceBytes[1] == UTF8_BOM[1] &&
            sourceBytes[2] == UTF8_BOM[2]
        ) {
            return sourceBytes.copyOfRange(3, sourceBytes.size).toString(Charsets.UTF_8)
        }
        if (sourceBytes.size >= 2 && sourceBytes[0] == UTF16_LE_BOM[0] && sourceBytes[1] == UTF16_LE_BOM[1]) {
            return sourceBytes.copyOfRange(2, sourceBytes.size).toString(Charsets.UTF_16LE)
        }
        if (sourceBytes.size >= 2 && sourceBytes[0] == UTF16_BE_BOM[0] && sourceBytes[1] == UTF16_BE_BOM[1]) {
            return sourceBytes.copyOfRange(2, sourceBytes.size).toString(Charsets.UTF_16BE)
        }
        if (sourceBytes.take(BINARY_PROBE_LENGTH).any { byte -> byte == 0.toByte() }) {
            throw IllegalArgumentException("Unsupported subtitle format.")
        }

        decodeWithCharset(sourceBytes, Charsets.UTF_8)?.let { return it }
        decodeWithCharset(sourceBytes, Charset.forName("windows-1252"))?.let { return it }
        return sourceBytes.toString(StandardCharsets.ISO_8859_1)
    }

    private fun decodeWithCharset(sourceBytes: ByteArray, charset: Charset): String? {
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(sourceBytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    companion object {
        private const val BINARY_PROBE_LENGTH = 512

        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        private val UNSAFE_FILE_NAME_REGEX = Regex("""[^A-Za-z0-9._-]+""")
    }
}
