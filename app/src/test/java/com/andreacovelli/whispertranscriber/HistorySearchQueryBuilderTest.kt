package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.history.buildHistorySearchQuery
import com.andreacovelli.whispertranscriber.history.extractHistorySearchTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class HistorySearchQueryBuilderTest {

    @Test
    fun extractHistorySearchTokens_returnsDistinctTokensInOrder() {
        assertEquals(
            listOf("meeting", "notes", "wav"),
            extractHistorySearchTokens("meeting notes.wav meeting"),
        )
    }

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
