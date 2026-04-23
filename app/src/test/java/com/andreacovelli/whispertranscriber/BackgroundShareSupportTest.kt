package com.andreacovelli.whispertranscriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundShareSupportTest {
    @Test
    fun splitNotificationTextPages_keepsShortTextOnOnePage() {
        assertEquals(
            listOf("Short result"),
            splitNotificationTextPages("Short result", maxChars = 20),
        )
    }

    @Test
    fun splitNotificationTextPages_preservesParagraphBoundariesWhenPossible() {
        val text = "First paragraph line.\n\nSecond paragraph starts here."

        val pages = splitNotificationTextPages(text, maxChars = 30)

        assertEquals(2, pages.size)
        assertTrue(pages[0].endsWith("\n\n"))
        assertEquals(text, pages.joinToString(separator = ""))
    }

    @Test
    fun splitNotificationTextPages_hardWrapsLongSingleToken() {
        val text = "abcdefghij"

        val pages = splitNotificationTextPages(text, maxChars = 4)

        assertEquals(listOf("abcd", "efgh", "ij"), pages)
    }

    @Test
    fun resolveNotificationTextPage_clampsOutOfRangePageIndex() {
        val page = resolveNotificationTextPage(
            text = "one two three four",
            pageIndex = 99,
            maxChars = 8,
        )

        assertEquals("four", page.text)
        assertEquals(2, page.index)
        assertEquals(3, page.total)
        assertTrue(page.hasPrevious)
        assertFalse(page.hasNext)
    }

    @Test
    fun resolveNotificationTextPage_reportsNavigationStateForMiddlePage() {
        val page = resolveNotificationTextPage(
            text = "one two three four",
            pageIndex = 1,
            maxChars = 8,
        )

        assertEquals("three ", page.text)
        assertEquals(1, page.index)
        assertEquals(3, page.total)
        assertTrue(page.hasPrevious)
        assertTrue(page.hasNext)
    }
}
