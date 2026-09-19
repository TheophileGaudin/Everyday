package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.video.sortedVideoFiles
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class VideoQueueRepositoryTest {
    private lateinit var directory: File

    @Before
    fun createDirectory() {
        directory = File(
            System.getProperty("java.io.tmpdir"),
            "everyday-video-queue-${System.nanoTime()}",
        )
        check(directory.mkdirs())
    }

    @After
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun `video siblings are filtered and sorted without case sensitivity`() {
        File(directory, "zeta.webm").createNewFile()
        File(directory, "Alpha.MP4").createNewFile()
        File(directory, "middle.mkv").createNewFile()
        File(directory, "notes.txt").createNewFile()

        assertEquals(
            listOf("Alpha.MP4", "middle.mkv", "zeta.webm"),
            sortedVideoFiles(directory).map { it.name },
        )
    }

    @Test
    fun `directories and unsupported files are not queue entries`() {
        check(File(directory, "folder.mp4").mkdir())
        File(directory, "poster.jpg").createNewFile()

        assertEquals(emptyList<File>(), sortedVideoFiles(directory))
    }
}