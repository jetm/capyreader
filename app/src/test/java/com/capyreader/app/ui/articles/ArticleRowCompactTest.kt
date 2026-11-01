package com.capyreader.app.ui.articles

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
class ArticleRowCompactTest {
    private val publishedAt = ZonedDateTime.parse("2026-11-01T09:00:00Z")

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `compact mode shortens titles further`() {
        assertEquals(2, titleMaxLines(shortenTitles = true, compact = true))
        assertEquals(3, titleMaxLines(shortenTitles = true, compact = false))
        assertEquals(Int.MAX_VALUE, titleMaxLines(shortenTitles = false, compact = true))
    }

    @Test
    fun `relative date counts hours and days back from now`() {
        assertEquals("2 hr. ago", relativeDate(time = publishedAt, now = millisAfter(Duration.ofHours(2))))
        assertEquals("3 days ago", relativeDate(time = publishedAt, now = millisAfter(Duration.ofDays(3))))
    }

    private fun millisAfter(duration: Duration): Long {
        return publishedAt.plus(duration).toInstant().toEpochMilli()
    }
}
