package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.widgets.file_browser.ImageImportClassifier
import com.everyday.everyday_glasses.widgets.file_browser.ImageImportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageImportClassifierTest {
    @Test
    fun recognizesSupportedImageHeaders() {
        assertEquals(ImageImportFormat.JPEG, ImageImportClassifier.classify(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals(ImageImportFormat.PNG, ImageImportClassifier.classify(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(ImageImportFormat.GIF, ImageImportClassifier.classify("GIF89a".encodeToByteArray()))
        assertEquals(ImageImportFormat.WEBP, ImageImportClassifier.classify("RIFF1234WEBP".encodeToByteArray()))
        assertEquals(ImageImportFormat.BMP, ImageImportClassifier.classify("BM".encodeToByteArray()))
    }

    @Test
    fun rejectsUnknownOrTruncatedHeaders() {
        assertNull(ImageImportClassifier.classify(byteArrayOf()))
        assertNull(ImageImportClassifier.classify("image/jpeg".encodeToByteArray()))
        assertNull(ImageImportClassifier.classify("RIFF1234".encodeToByteArray()))
    }
}
