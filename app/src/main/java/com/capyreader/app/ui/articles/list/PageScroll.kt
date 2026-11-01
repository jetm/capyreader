package com.capyreader.app.ui.articles.list

import com.capyreader.app.ui.articles.reader.PageDirection
import kotlin.math.abs

data class PageItemBounds(val index: Int, val offset: Int, val size: Int)

sealed interface ForwardTurn {
    data class ToItem(val index: Int) : ForwardTurn

    data class ByPixels(val pixels: Float) : ForwardTurn

    data object None : ForwardTurn
}

fun forwardTurn(
    items: List<PageItemBounds>,
    visibleStart: Int,
    visibleEnd: Int,
): ForwardTurn {
    val cut = items.firstOrNull { it.offset + it.size > visibleEnd }

    return when {
        cut != null && cut.offset <= visibleStart ->
            ForwardTurn.ByPixels((visibleEnd - visibleStart).toFloat())

        cut != null -> ForwardTurn.ToItem(cut.index)
        items.isNotEmpty() -> ForwardTurn.ToItem(items.last().index + 1)
        else -> ForwardTurn.None
    }
}

fun backAlignPixels(
    items: List<PageItemBounds>,
    visibleStart: Int,
    visibleEnd: Int,
    previousFirstIndex: Int,
): Int {
    val top = items.firstOrNull { it.offset + it.size > visibleStart } ?: return 0
    val align = top.offset + top.size - visibleStart
    val previousFirst = items.firstOrNull { it.index == previousFirstIndex }

    val leavesPreviousCut = previousFirst == null ||
        previousFirst.offset + previousFirst.size - align > visibleEnd

    return if (
        top.offset < visibleStart &&
        top.index + 1 < previousFirstIndex &&
        leavesPreviousCut
    ) {
        align
    } else {
        0
    }
}

fun pageTurnDirection(
    dragPixels: Float,
    releaseVelocity: Float,
    minDragPixels: Float,
    minFlingVelocity: Float,
): PageDirection? {
    return when {
        abs(dragPixels) >= minDragPixels -> directionOf(dragPixels)
        abs(releaseVelocity) >= minFlingVelocity -> directionOf(releaseVelocity)
        else -> null
    }
}

private fun directionOf(signed: Float): PageDirection {
    return if (signed < 0f) PageDirection.FORWARD else PageDirection.BACK
}

fun consumesPreScroll(
    isUserInput: Boolean,
    delta: Float,
    gestureOpen: Boolean,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): Boolean {
    return isUserInput &&
        delta != 0f &&
        gestureOpen &&
        !(delta > 0f && !canScrollBackward) &&
        !(delta < 0f && !canScrollForward)
}
