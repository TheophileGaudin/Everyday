package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.WidgetPersistenceTestSupport.roundTrip
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets.file_browser.PdfWidgetState
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfDashboardPersistenceTest {

    @Test
    fun pdfWidgetRoundTripsThroughPersistedJson() {
        val pdf = PdfWidgetState(
            x = 30f,
            y = 20f,
            width = 360f,
            height = 260f,
            uri = "content://media/external/downloads/17",
            displayName = "manual.pdf",
            page = 6,
            zoom = 1.8f,
            isPinned = true,
        )

        assertEquals(pdf, roundTrip(WidgetTypeId.PDF, pdf))
    }
}
