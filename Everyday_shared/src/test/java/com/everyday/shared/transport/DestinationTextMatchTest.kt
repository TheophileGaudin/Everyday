package com.everyday.shared.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationTextMatchTest {
    @Test
    fun regionalSpellingsAndLeadingArticlesRemainExactMatches() {
        val match = DestinationTextMatcher.match(
            "Hive leisure center",
            "The Hive Leisure Centre",
            "Leisure Park, Downham Road"
        )

        assertEquals(1.0, match.score, 0.0001)
        assertEquals(1.0, match.tokenCoverage, 0.0001)
    }

    @Test
    fun unrelatedShortTokensDoNotCountAsSubstringMatches() {
        val match = DestinationTextMatcher.match("a st", "Coastal Restaurant")

        assertTrue(match.tokenCoverage < 1.0)
    }
}
