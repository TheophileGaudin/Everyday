package com.everyday.everyday_glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfWidgetPersistenceTest {

    @Test
    fun pdfWidgetRoundTripsThroughPersistedJson() {
        val pdf = WidgetPersistence.PdfWidgetState(
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
        val state = WidgetPersistence.createPersistedState(
            textWidgets = emptyList(),
            browserWidgets = emptyList(),
            imageWidgets = emptyList(),
            statusBarState = null,
            pdfWidgets = listOf(pdf),
        )

        val restored = WidgetPersistence.stateFromJson(WidgetPersistence.stateToJson(state))

        assertEquals(listOf(pdf), restored.pdf)
    }
}
