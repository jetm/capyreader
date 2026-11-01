package com.capyreader.app.ui.articles.list

import com.capyreader.app.ui.articles.reader.PageDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageScrollTest {
    private fun forward(
        items: List<PageItemBounds>,
        start: Int = 0,
        end: Int = 800,
    ) = forwardTurn(items = items, visibleStart = start, visibleEnd = end)

    private fun back(
        items: List<PageItemBounds>,
        previousFirstIndex: Int = 7,
        start: Int = 0,
        end: Int = 800,
    ) = backAlignPixels(
        items = items,
        visibleStart = start,
        visibleEnd = end,
        previousFirstIndex = previousFirstIndex,
    )

    private fun direction(drag: Float, velocity: Float = 0f) =
        pageTurnDirection(
            dragPixels = drag,
            releaseVelocity = velocity,
            minDragPixels = 24f,
            minFlingVelocity = 50f,
        )

    private fun zone(x: Float) =
        tapZoneDirection(x = x, width = 1000f, edgeFraction = 0.25f)

    private fun tap(distance: Float, duration: Long) =
        isTap(
            distancePixels = distance,
            durationMillis = duration,
            touchSlopPixels = 8f,
            longPressMillis = 400L,
        )

    private fun consumes(
        isUserInput: Boolean = true,
        delta: Float = -50f,
        gestureOpen: Boolean = true,
        canScrollBackward: Boolean = true,
        canScrollForward: Boolean = true,
    ) = consumesPreScroll(
        isUserInput = isUserInput,
        delta = delta,
        gestureOpen = gestureOpen,
        canScrollBackward = canScrollBackward,
        canScrollForward = canScrollForward,
    )

    @Test
    fun `forward turn goes to the first article cut at the bottom`() {
        val items = listOf(
            PageItemBounds(0, 0, 300),
            PageItemBounds(1, 300, 300),
            PageItemBounds(2, 600, 300),
        )

        assertEquals(ForwardTurn.ToItem(2), forward(items))
    }

    @Test
    fun `forward turn goes past the last item when everything fits exactly`() {
        val items = listOf(
            PageItemBounds(0, 0, 400),
            PageItemBounds(1, 400, 400),
        )

        assertEquals(ForwardTurn.ToItem(2), forward(items))
    }

    @Test
    fun `forward turn scrolls a page when the top article is taller than the region`() {
        assertEquals(
            ForwardTurn.ByPixels(800f),
            forward(listOf(PageItemBounds(0, 0, 1200))),
        )
        assertEquals(
            ForwardTurn.ByPixels(800f),
            forward(listOf(PageItemBounds(0, -300, 1500))),
        )
        assertEquals(
            ForwardTurn.ByPixels(800f),
            forward(listOf(PageItemBounds(0, 100, 1000)), start = 100, end = 900),
        )
    }

    @Test
    fun `forward turn skips an article that ends above the bottom`() {
        val items = listOf(
            PageItemBounds(7, -200, 400),
            PageItemBounds(8, 200, 700),
        )

        assertEquals(ForwardTurn.ToItem(8), forward(items))
    }

    @Test
    fun `forward turn on an empty list does nothing`() {
        assertEquals(ForwardTurn.None, forward(emptyList()))
    }

    @Test
    fun `back align leaves the old top article cut at the bottom`() {
        val items = listOf(
            PageItemBounds(4, -120, 300),
            PageItemBounds(5, 180, 300),
            PageItemBounds(6, 480, 300),
            PageItemBounds(7, 780, 300),
        )

        assertEquals(180, back(items))
    }

    @Test
    fun `back align is zero when the top article is already aligned`() {
        val items = listOf(
            PageItemBounds(4, 0, 300),
            PageItemBounds(5, 300, 300),
            PageItemBounds(6, 600, 300),
            PageItemBounds(7, 900, 300),
        )

        assertEquals(0, back(items))
    }

    @Test
    fun `back align is zero when the top article is taller than the region`() {
        val items = listOf(
            PageItemBounds(6, -400, 1200),
            PageItemBounds(7, 800, 300),
        )

        assertEquals(0, back(items))
    }

    @Test
    fun `back align ignores an article ending exactly at the top`() {
        val items = listOf(
            PageItemBounds(3, -200, 200),
            PageItemBounds(4, 0, 300),
        )

        assertEquals(0, back(items))
    }

    @Test
    fun `back align is zero when aligning would bring the old top article fully into view`() {
        val items = listOf(
            PageItemBounds(8, -100, 300),
            PageItemBounds(9, 200, 600),
            PageItemBounds(10, 800, 100),
        )

        assertEquals(0, back(items, previousFirstIndex = 10))
    }

    @Test
    fun `back align assumes the old top article is cut when it is not laid out`() {
        val items = listOf(
            PageItemBounds(4, -120, 300),
            PageItemBounds(5, 180, 300),
            PageItemBounds(6, 480, 300),
        )

        assertEquals(180, back(items))
    }

    @Test
    fun `back align is zero when the cut article sits directly above the old top article`() {
        val items = listOf(
            PageItemBounds(6, -100, 300),
            PageItemBounds(7, 200, 700),
        )

        assertEquals(0, back(items))
    }

    @Test
    fun `back align is zero when the old top article is taller than the region`() {
        val items = listOf(
            PageItemBounds(6, -100, 300),
            PageItemBounds(7, 200, 1500),
        )

        assertEquals(0, back(items))
    }

    @Test
    fun `back align measures against the start of the visible region`() {
        val items = listOf(
            PageItemBounds(4, -20, 300),
            PageItemBounds(5, 280, 300),
            PageItemBounds(6, 580, 300),
            PageItemBounds(7, 880, 300),
        )

        assertEquals(180, back(items, start = 100, end = 900))
    }

    @Test
    fun `consumes a user drag when the gesture is open`() {
        assertTrue(consumes())
    }

    @Test
    fun `does not consume when the gesture is closed`() {
        assertFalse(consumes(gestureOpen = false))
    }

    @Test
    fun `does not consume programmatic scrolls`() {
        assertFalse(consumes(isUserInput = false))
    }

    @Test
    fun `does not consume a backward drag at the start`() {
        assertFalse(consumes(delta = 50f, canScrollBackward = false))
    }

    @Test
    fun `does not consume a forward drag at the end`() {
        assertFalse(consumes(delta = -50f, canScrollForward = false))
    }

    @Test
    fun `does not consume a zero delta`() {
        assertFalse(consumes(delta = 0f))
    }

    @Test
    fun `a long drag turns the page in its direction`() {
        assertEquals(PageDirection.FORWARD, direction(-30f))
        assertEquals(PageDirection.BACK, direction(30f))
        assertEquals(PageDirection.FORWARD, direction(-24f))
    }

    @Test
    fun `a short drag with a fling turns the page`() {
        assertEquals(PageDirection.FORWARD, direction(-10f, -100f))
        assertEquals(PageDirection.BACK, direction(-10f, 100f))
    }

    @Test
    fun `a drag or fling exactly at the minimum turns the page`() {
        assertEquals(PageDirection.BACK, direction(24f))
        assertEquals(PageDirection.FORWARD, direction(-10f, -50f))
    }

    @Test
    fun `a short slow drag turns nothing`() {
        assertNull(direction(-10f, -10f))
    }

    @Test
    fun `a long drag wins over an opposite fling`() {
        assertEquals(PageDirection.FORWARD, direction(-200f, 80f))
    }

    @Test
    fun `a tap in the left quarter turns back`() {
        assertEquals(PageDirection.BACK, zone(0f))
        assertEquals(PageDirection.BACK, zone(249f))
    }

    @Test
    fun `a tap in the middle half turns nothing`() {
        assertNull(zone(250f))
        assertNull(zone(500f))
        assertNull(zone(750f))
    }

    @Test
    fun `a tap in the right quarter turns forward`() {
        assertEquals(PageDirection.FORWARD, zone(751f))
        assertEquals(PageDirection.FORWARD, zone(1000f))
    }

    @Test
    fun `a tap in a list with no width turns nothing`() {
        assertNull(tapZoneDirection(x = 10f, width = 0f, edgeFraction = 0.25f))
    }

    @Test
    fun `a short still press is a tap`() {
        assertTrue(tap(0f, 100))
        assertTrue(tap(8f, 100))
        assertTrue(tap(0f, 400))
    }

    @Test
    fun `a press that moved or lingered is not a tap`() {
        assertFalse(tap(9f, 100))
        assertFalse(tap(0f, 401))
        assertFalse(tap(20f, 600))
    }
}
