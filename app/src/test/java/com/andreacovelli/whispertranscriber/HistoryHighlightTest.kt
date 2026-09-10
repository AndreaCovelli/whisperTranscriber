package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.ui.components.buildPrefixMatchRegex
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryHighlightTest {
    @Test
    fun everySearchAlternative_requiresAWordBoundary() {
        val regex = checkNotNull(buildPrefixMatchRegex(listOf("alpha", "beta")))
        assertEquals(listOf("alpha", "beta"), regex.findAll("xalpha alphabet xbeta beta").map { it.value }.toList())
    }
}
