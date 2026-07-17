package com.everyday.everyday_glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoWidgetPersistenceTest {

    @Test
    fun videoWidgetRoundTripsThroughPersistedJson() {
        val video = WidgetPersistence.VideoWidgetState(
            x = 40f,
            y = 30f,
            width = 360f,
            height = 220f,
            uri = "content://media/external/video/media/42",
            displayName = "sample.mp4",
            isPinned = true,
        )
        val state = WidgetPersistence.createPersistedState(
            textWidgets = emptyList(),
            browserWidgets = emptyList(),
            imageWidgets = emptyList(),
            statusBarState = null,
            videoWidgets = listOf(video),
        )

        val restored = WidgetPersistence.stateFromJson(WidgetPersistence.stateToJson(state))

        assertEquals(listOf(video), restored.video)
    }
}
