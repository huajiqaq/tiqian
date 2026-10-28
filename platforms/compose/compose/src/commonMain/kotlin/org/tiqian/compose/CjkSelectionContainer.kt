@file:Suppress("DEPRECATION")

package org.tiqian.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Remembers state for one [CjkSelectionContainer]. */
@Composable
fun rememberCjkSelectionState(): CjkSelectionState = remember { CjkSelectionState() }

/**
 * A host-supplied action appended to the selection context menu, next to Copy / Select All.
 *
 * Hosts use this to add selection-scoped behaviour (saving a highlight, opening a comment thread)
 * without forking the renderer or reflection-reading internal selection state.
 */
class CjkSelectionMenuAction(
    /** Stable identifier, used for menu ordering and accessibility. */
    val id: String,
    /** Menu label shown to the user. */
    val label: String,
    /** Called with the currently selected text when the user activates the item. */
    val onInvoke: (String) -> Unit,
)

/**
 * Host-rendered replacement for the platform selection menu.
 *
 * When provided, the container suppresses the platform menu and asks the host to draw the whole
 * menu instead, so an app can keep its selection UI visually identical across renderers
 * (for example when one renderer uses the platform menu and another cannot).
 *
 * [onCopy] / [onSelectAll] / [onClose] are the canonical system behaviours; [selectedText]
 * is the current selection, and [actions] are the host-supplied extra items.
 */
class CjkSelectionMenuContent(
    /** 精确的选中文本（按选区起止裁剪，不是整段）。 */
    val selectedText: () -> String,
    val onCopy: () -> Unit,
    val onSelectAll: () -> Unit,
    val onClose: () -> Unit,
    /** 选区在根容器坐标系里的包围盒；菜单据此定位。 */
    val selectionBoundsInRoot: () -> androidx.compose.ui.geometry.Rect?,
    val actions: List<CjkSelectionMenuAction>,
)

/**
 * Enables source-faithful static-text selection for descendant `CjkText` surfaces. Unlike
 * Compose's `SelectionContainer`, this container consumes Tiqian [org.tiqian.core.LayoutResult]
 * geometry directly, so line breaks, punctuation glue, ruby expansion, and copied source ranges do
 * not pass through a hidden second text layout. [document] supplies stable logical fragments for a
 * virtualized document; selection and copying then survive off-screen disposal.
 *
 * When [content] uses `Modifier.verticalScroll`, pass that modifier's [scrollState] here as well.
 * A mouse, touch, or handle drag then scrolls inside the edge bands after the gesture has crossed
 * touch slop. `null` disables auto-scroll.
 *
 * [selectionActions] is appended to the platform selection menu after Copy / Select All.
 */
@Composable
fun CjkSelectionContainer(
    modifier: Modifier = Modifier,
    state: CjkSelectionState = rememberCjkSelectionState(),
    scrollState: ScrollState? = null,
    autoScrollEdgeSize: Dp = 48.dp,
    autoScrollMaxVelocity: Dp = 1_200.dp,
    document: CjkSelectionDocument? = null,
    selectionActions: List<CjkSelectionMenuAction> = emptyList(),
    /**
     * 宿主自绘选区菜单。提供后不再显示平台菜单，保证与其它渲染引擎视觉一致。
     */
    selectionMenuContent: (@Composable (CjkSelectionMenuContent) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val textToolbar = LocalTextToolbar.current
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val colors = LocalTextSelectionColors.current
    SideEffect {
        state.attach(
            clipboardManager,
            textToolbar,
            hapticFeedback,
            colors.backgroundColor.toArgb(),
            foundationSelectionToolbarHandleClearancePx(density),
            document,
        )
    }
    CjkSelectionAutoScrollEffect(
        state = state,
        scrollState = scrollState,
        edgeSize = autoScrollEdgeSize,
        maxVelocity = autoScrollMaxVelocity,
    )

    Box(
        modifier = modifier
            .foundationClearSelectionOnTap(state)
            .foundationSelectionMagnifier(state)
            .onGloballyPositioned(state::updateContainerCoordinates)
            .focusRequester(state.focusRequester)
            .onFocusChanged { focus ->
                // 宿主自绘菜单（selectionMenuContent）是独立弹窗：打开时会拿走焦点，
                // 且 focusable 弹窗的外部触摸也会先触发 dismiss。两种情况都不代表
                // 用户放弃了选区，此时清选区会导致拖拽手柄直接消失。
                // 平台菜单与选区同窗口、由引擎管理关闭，保留原行为。
                if (selectionMenuContent == null &&
                    !focus.hasFocus &&
                    state.hasSelection
                ) {
                    state.clearSelection()
                }
            }
            .onKeyEvent(state::handleCopyKeyEvent)
            // A low-level focus target is enough for keyboard copy/Escape. `focusable()` also
            // publishes an otherwise empty accessibility node around every descendant text node.
            .focusTarget(),
    ) {
        FoundationSelectionContextMenuArea(state, selectionActions, selectionMenuContent) {
            CompositionLocalProvider(LocalCjkSelectionState provides state) {
                content()
            }
            if (state.isTouchSelection && state.hasSelection) {
                CjkSelectionHandle(state, isStart = true)
                CjkSelectionHandle(state, isStart = false)
            }
        }
    }

    DisposableEffect(state) {
        onDispose(state::detach)
    }
}

/** Prevents descendant `CjkText` nodes from registering in an outer [CjkSelectionContainer]. */
@Composable
fun CjkDisableSelection(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCjkSelectionState provides null, content = content)
}

/**
 * Associates one descendant [CjkText] with a stable [CjkSelectionDocumentFragment] key.
 */
@Composable
fun CjkSelectionScope(
    ownerKey: Any,
    retentionKey: Any = ownerKey,
    content: @Composable () -> Unit,
) {
    val scope = remember(ownerKey, retentionKey) {
        CjkSelectionScopeInfo(ownerKey, retentionKey)
    }
    CompositionLocalProvider(LocalCjkSelectionScope provides scope, content = content)
}

@Composable
private fun CjkSelectionHandle(
    state: CjkSelectionState,
    isStart: Boolean,
) {
    FoundationSelectionHandle(
        offsetProvider = {
            (if (isStart) state.startHandlePosition else state.endHandlePosition)
                ?: Offset.Unspecified
        },
        isStartHandle = isStart,
        handlesCrossed = state.handlesCrossed,
        lineHeight = if (isStart) state.startHandleLineHeight else state.endHandleLineHeight,
        modifier = Modifier.foundationSelectionHandleGestures(state, isStart),
    )
}

internal val LocalCjkSelectionState = compositionLocalOf<CjkSelectionState?> { null }
internal val LocalCjkSelectionScope = compositionLocalOf<CjkSelectionScopeInfo?> { null }
