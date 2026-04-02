package com.andreacovelli.whispertranscriber

import org.junit.Assert.assertEquals
import org.junit.Test

class HistorySearchQueryBuilderTest {

    @Test
    fun buildHistorySearchQuery_normalizesSearchTermsIntoFtsPrefixes() {
        assertEquals(
            "meeting* notes* wav*",
            buildHistorySearchQuery("meeting notes.wav"),
        )
    }

    @Test
    fun buildHistorySearchQuery_deduplicatesAndDropsUnsupportedCharacters() {
        assertEquals(
            "alpha* beta*",
            buildHistorySearchQuery("alpha ### beta alpha"),
        )
    }
}
