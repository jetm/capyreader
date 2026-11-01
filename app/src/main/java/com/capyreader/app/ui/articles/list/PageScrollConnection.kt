package com.capyreader.app.ui.articles.list

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.capyreader.app.ui.articles.reader.PageDirection
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class PageScrollConnection(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val minDragPixels: Float,
    private val minFlingVelocity: Float,
) : NestedScrollConnection {
    private var touchHeld = false
    private var travel = 0f
    private var consumed = false
    private var passedThrough = false
    private var liftedAfterDrag = false
    private var turnJob: Job? = null
    private var liftExpiry: Job? = null

    suspend fun trackTouches(pointerScope: PointerInputScope) {
        with(pointerScope) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )

                if (down.type == PointerType.Mouse) {
                    return@awaitEachGesture
                }

                touchHeld = true
                travel = 0f
                consumed = false
                passedThrough = false
                liftedAfterDrag = false
                turnJob?.cancel()
                liftExpiry?.cancel()

                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull { it.id == down.id }?.let {
                            travel = it.position.y - down.position.y
                        }
                    } while (event.changes.any { it.pressed })
                } finally {
                    touchHeld = false
                    liftedAfterDrag = abs(travel) > viewConfiguration.touchSlop
                    expireLift()
                }
            }
        }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val gestureOpen = touchHeld || consumed || liftedAfterDrag

        val consumes = consumesPreScroll(
            isUserInput = source == NestedScrollSource.UserInput,
            delta = available.y,
            gestureOpen = gestureOpen,
            canScrollBackward = listState.canScrollBackward,
            canScrollForward = listState.canScrollForward,
        )

        if (!consumes) {
            if (source == NestedScrollSource.UserInput && gestureOpen && isEdgePassThrough(available.y)) {
                passedThrough = true
            }

            return Offset.Zero
        }

        consumed = true

        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        liftedAfterDrag = false
        liftExpiry?.cancel()

        if (!consumed) {
            return Velocity.Zero
        }

        consumed = false

        if (passedThrough) {
            return available
        }

        val direction = pageTurnDirection(
            dragPixels = travel,
            releaseVelocity = available.y,
            minDragPixels = minDragPixels,
            minFlingVelocity = minFlingVelocity,
        )

        if (direction != null) {
            turnJob?.cancel()
            turnJob = scope.launch { turn(direction) }
        }

        return available
    }

    private fun isEdgePassThrough(delta: Float): Boolean {
        return (delta > 0f && !listState.canScrollBackward) ||
            (delta < 0f && !listState.canScrollForward)
    }

    private fun expireLift() {
        if (!liftedAfterDrag) {
            return
        }

        liftExpiry?.cancel()
        liftExpiry = scope.launch {
            withFrameNanos { }
            withFrameNanos { }

            if (!consumed) {
                liftedAfterDrag = false
            }
        }
    }

    private suspend fun turn(direction: PageDirection) {
        snapshotFlow { listState.isScrollInProgress }.first { !it }

        when (direction) {
            PageDirection.FORWARD -> turnForward()
            PageDirection.BACK -> turnBack()
        }
    }

    private suspend fun turnForward() {
        if (!listState.canScrollForward) {
            return
        }

        val info = listState.layoutInfo
        val visibleStart = info.viewportStartOffset + info.beforeContentPadding
        val visibleEnd = info.viewportEndOffset - info.afterContentPadding
        val items = info.visibleItemsInfo.map { PageItemBounds(it.index, it.offset, it.size) }

        when (val turn = forwardTurn(items, visibleStart, visibleEnd)) {
            is ForwardTurn.ToItem -> listState.animateScrollToItem(turn.index)
            is ForwardTurn.ByPixels -> listState.animateScrollBy(turn.pixels)
            ForwardTurn.None -> Unit
        }
    }

    private suspend fun turnBack() {
        if (!listState.canScrollBackward) {
            return
        }

        val info = listState.layoutInfo
        val visibleStart = info.viewportStartOffset + info.beforeContentPadding
        val visibleEnd = info.viewportEndOffset - info.afterContentPadding

        val previousFirst = listState.firstVisibleItemIndex
        val back = listState.scrollBy(-(visibleEnd - visibleStart).toFloat())

        val after = listState.layoutInfo
        val align = backAlignPixels(
            items = after.visibleItemsInfo.map { PageItemBounds(it.index, it.offset, it.size) },
            visibleStart = visibleStart,
            visibleEnd = visibleEnd,
            previousFirstIndex = previousFirst,
        )

        val aligned = if (align > 0) listState.scrollBy(align.toFloat()) else 0f
        // backAlignPixels assumes the old top article is cut when it was not laid out; this confirms it after aligning.
        val previousTop = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == previousFirst }
        val keepsPreviousCut = previousTop == null || previousTop.offset + previousTop.size > visibleEnd
        val effectiveAlign = if (keepsPreviousCut) aligned else 0f

        listState.scrollBy(-(back + aligned))
        listState.animateScrollBy(back + effectiveAlign)
    }
}

@Composable
internal fun rememberPageScrollConnection(listState: LazyListState): PageScrollConnection {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val minDragPixels = with(LocalDensity.current) { 24.dp.toPx() }
    val minFlingVelocity = android.view.ViewConfiguration.get(context)
        .scaledMinimumFlingVelocity
        .toFloat()

    return remember(listState) {
        PageScrollConnection(
            listState = listState,
            scope = scope,
            minDragPixels = minDragPixels,
            minFlingVelocity = minFlingVelocity,
        )
    }
}

internal fun Modifier.pageScroll(connection: PageScrollConnection): Modifier {
    return pointerInput(connection) { connection.trackTouches(this) }
        .nestedScroll(connection)
}
