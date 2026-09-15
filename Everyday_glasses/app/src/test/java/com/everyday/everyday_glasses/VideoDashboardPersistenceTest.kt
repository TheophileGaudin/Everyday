package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.WidgetPersistenceTestSupport.roundTrip
import com.everyday.everyday_glasses.data_models.WidgetTypeId
import com.everyday.everyday_glasses.stores.DashboardPersistence
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDashboardPersistenceTest {

    @Test
    fun videoWidgetRoundTripsThroughPersistedJson() {
        val video = DashboardPersistence.VideoWidgetState(
            x = 40f,
            y = 30f,
            width = 360f,
            height = 220f,
            uri = "content://media/external/video/media/42",
            displayName = "sample.mp4",
            isPinned = true,
        )

        assertEquals(video, roundTrip(WidgetTypeId.VIDEO, video))
    }
}
