@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.tiqian.compose

import androidx.compose.foundation.text.selection.OffsetProvider
import androidx.compose.foundation.text.TextDragObserver
import androidx.compose.foundation.text.detectDownAndDragGesturesWithObserver
import androidx.compose.foundation.text.selection.MouseSelectionObserver
import androidx.compose.foundation.text.selection.SelectionAdjustment
import androidx.compose.foundation.text.selection.SelectionHandle
import androidx.compose.foundation.text.selection.HandleHeight
import androidx.compose.foundation.text.selection.awaitSelectionGestures
import androidx.compose.foundation.gestures.awaitAllPointersUpWithSlopDetection
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize

/**
 * Version-pinned bridge to Compose Foundation's real platform selection handle.
 *
 * Foundation does not expose a selectable adapter for third-party text layouts, but its handle is
 * already an expect/actual implementation with the correct Android and Skiko appearance, popup
 * anchoring, semantics, touch target propagation, and system-gesture behavior. Keep the internal
 * dependency isolated here so a Compose upgrade has one explicit compatibility boundary.
 */
@Composable
internal fun FoundationSelectionHandle(
    offsetProvider: () -> Offset,
    isStartHandle: Boolean,
    handlesCrossed: Boolean,
    lineHeight: Float,
    modifier: Modifier,
) {
    SelectionHandle(
        offsetProvider = object : OffsetProvider {
            override fun provide(): Offset = offsetProvider()
        },
        isStartHandle = isStartHandle,
        direction = ResolvedTextDirection.Ltr,
        handlesCrossed = handlesCrossed,
        minTouchTargetSize = DpSize.Unspecified,
        lineHeight = lineHeight,
        modifier = modifier,
    )
}

/** Selection policies named at Tiqian's Foundation compatibility boundary. */
internal enum class CjkSelectionAdjustment {
    None,
    Word,
    Paragraph,
    CharacterWithWordAccelerate,
}

/**
 * Uses Foundation's own pointer-type detection, click counter, long-press timing, touch-slop
 * handling, and mouse/touch gesture state machine. The observers translate only positions and
 * adjustment policy into Tiqian source/layout geometry.
 */
internal fun Modifier.foundationSelectionGestures(
    state: CjkSelectionState,
    bridge: CjkTextSelectionBridge,
): Modifier =
    onGloballyPositioned { coordinates ->
        bridge.coordinates = coordinates
        bridge.selectable?.updateSelectionCoordinates(coordinates)
    }
        .pointerInput(state, bridge) {
            var mouseAdjustment = CjkSelectionAdjustment.None
            var mouseDragged = false
            val mouseObserver = object : MouseSelectionObserver {
                override fun onExtend(downPosition: Offset): Boolean {
                    val selectable = bridge.selectable ?: return false
                    mouseDragged = false
                    mouseAdjustment = CjkSelectionAdjustment.None
                    return state.extendGestureSelection(selectable, downPosition)
                }

                override fun onExtendDrag(dragPosition: Offset): Boolean {
                    val selectable = bridge.selectable ?: return false
                    if (!mouseDragged) state.armSelectionAutoScroll()
                    mouseDragged = true
                    return state.updateGestureSelection(
                        selectable,
                        dragPosition,
                        CjkSelectionAdjustment.None,
                    )
                }

                override fun onStart(
                    downPosition: Offset,
                    adjustment: SelectionAdjustment,
                    clickCount: Int,
                ): Boolean {
                    val selectable = bridge.selectable ?: return false
                    mouseAdjustment = adjustment.toCjkAdjustment()
                    mouseDragged = false
                    return state.beginGestureSelection(
                        selectable,
                        downPosition,
                        mouseAdjustment,
                        touch = false,
                    )
                }

                override fun onDrag(
                    dragPosition: Offset,
                    adjustment: SelectionAdjustment,
                ): Boolean {
                    val selectable = bridge.selectable ?: return false
                    if (!mouseDragged) state.armSelectionAutoScroll()
                    mouseDragged = true
                    return state.updateGestureSelection(
                        selectable,
                        dragPosition,
                        adjustment.toCjkAdjustment(),
                    )
                }

                override fun onDragDone() {
                    if (!mouseDragged && mouseAdjustment == CjkSelectionAdjustment.None) {
                        state.clearSelection()
                    } else {
                        state.finishSelection()
                    }
                }
            }

            val touchObserver = object : TextDragObserver {
                private var currentPosition = Offset.Unspecified
                private var pendingDrag = Offset.Zero
                private var dragStarted = false
                private var adjustment = CjkSelectionAdjustment.Word

                override fun onDown(point: Offset) = Unit

                override fun onUp() = Unit

                override fun onStart(
                    startPoint: Offset,
                    selectionAdjustment: SelectionAdjustment,
                ) {
                    val selectable = bridge.selectable ?: return
                    currentPosition = startPoint
                    pendingDrag = Offset.Zero
                    dragStarted = false
                    adjustment = selectionAdjustment.toCjkAdjustment()
                    state.beginGestureSelection(
                        selectable,
                        startPoint,
                        adjustment,
                        touch = true,
                    )
                }

                override fun onDrag(delta: Offset) {
                    if (!currentPosition.isSpecified) return
                    val selectable = bridge.selectable ?: return
                    pendingDrag += delta
                    if (!dragStarted) {
                        if (pendingDrag.getDistance() < viewConfiguration.touchSlop) return
                        dragStarted = true
                        state.armSelectionAutoScroll()
                    }
                    currentPosition += pendingDrag
                    pendingDrag = Offset.Zero
                    state.updateGestureSelection(selectable, currentPosition, adjustment)
                }

                override fun onStop() {
                    currentPosition = Offset.Unspecified
                    pendingDrag = Offset.Zero
                    dragStarted = false
                    state.finishSelection()
                }

                override fun onCancel() {
                    currentPosition = Offset.Unspecified
                    pendingDrag = Offset.Zero
                    dragStarted = false
                    state.finishSelection()
                }
            }

            awaitSelectionGestures(mouseObserver, touchObserver)
        }
        .pointerHoverIcon(PointerIcon.Text)

/**
 * Foundation SelectionManager's exact release timing: a primary press/release that never crosses
 * touch slop clears the settled selection, while a long-press or selection drag still in progress
 * owns the up event. This lives on the container so taps in blank space and non-Tiqian children are
 * covered as well.
 */
internal fun Modifier.foundationClearSelectionOnTap(state: CjkSelectionState): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            val primaryFirstDown = awaitFirstDown(requireUnconsumed = false)
            val pointerSlopReached = awaitAllPointersUpWithSlopDetection(
                primaryFirstDown,
                PointerEventPass.Initial,
            )
            if (!pointerSlopReached && !state.isSelectionGestureInProgress) {
                state.clearSelection()
            }
        }
    }

/** Uses Foundation's exact handle-drag detector, including pre-drag down/up delivery. */
internal fun Modifier.foundationSelectionHandleGestures(
    state: CjkSelectionState,
    isStartHandle: Boolean,
): Modifier = pointerInput(state, isStartHandle) {
    val observer = object : TextDragObserver {
        override fun onDown(point: Offset) {
            state.beginHandleDrag(isStartHandle)
        }

        override fun onUp() {
            state.endHandleDrag()
        }

        override fun onStart(startPoint: Offset, selectionAdjustment: SelectionAdjustment) {
            state.armSelectionAutoScroll()
        }

        override fun onDrag(delta: Offset) {
            state.dragHandle(isStartHandle, delta)
        }

        override fun onStop() {
            state.endHandleDrag()
        }

        override fun onCancel() {
            state.endHandleDrag()
        }
    }
    detectDownAndDragGesturesWithObserver(observer)
}

private fun SelectionAdjustment.toCjkAdjustment(): CjkSelectionAdjustment = when {
    this === SelectionAdjustment.Word -> CjkSelectionAdjustment.Word
    this === SelectionAdjustment.Paragraph -> CjkSelectionAdjustment.Paragraph
    this === SelectionAdjustment.CharacterWithWordAccelerate ->
        CjkSelectionAdjustment.CharacterWithWordAccelerate
    else -> CjkSelectionAdjustment.None
}

/** Android supplies the text magnifier; desktop deliberately returns this modifier unchanged. */
internal expect fun Modifier.foundationSelectionMagnifier(state: CjkSelectionState): Modifier

/** Foundation reserves four handle heights below the selected region for toolbar placement. */
internal fun foundationSelectionToolbarHandleClearancePx(density: Density): Float =
    with(density) { HandleHeight.toPx() * 4f }

/** Foundation's ancestor-clipped visible bounds, expressed in these local coordinates. */
internal fun LayoutCoordinates.visibleBoundsForSelection(): androidx.compose.ui.geometry.Rect {
    val windowBounds = boundsInWindow()
    return androidx.compose.ui.geometry.Rect(
        windowToLocal(windowBounds.topLeft),
        windowToLocal(windowBounds.bottomRight),
    )
}

internal fun androidx.compose.ui.geometry.Rect.containsInclusive(offset: Offset): Boolean =
    offset.x in left..right && offset.y in top..bottom

/** Platform context-menu bridge matching the Compose Foundation version pinned by this module. */
@Composable
internal expect fun FoundationSelectionContextMenuArea(
    state: CjkSelectionState,
    selectionActions: List<CjkSelectionMenuAction> = emptyList(),
    selectionMenuContent: (@Composable (CjkSelectionMenuContent) -> Unit)? = null,
    content: @Composable () -> Unit,
)
