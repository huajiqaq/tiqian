package org.tiqian.compose

import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString

/** Desktop keeps Foundation's current native/right-click text-menu contract. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun FoundationSelectionContextMenuArea(
    state: CjkSelectionState,
    selectionActions: List<CjkSelectionMenuAction>,
    selectionMenuContent: (@Composable (CjkSelectionMenuContent) -> Unit)?,
    content: @Composable () -> Unit,
) {
    val contextMenuState = remember { ContextMenuState() }
    val manager = remember(state) {
        object : TextContextMenu.TextManager {
            override val selectedText: AnnotatedString
                get() = state.selectedText ?: AnnotatedString("")
            override val cut: TextContextMenu.Action? = null
            override val copy: TextContextMenu.Action
                get() = TextContextMenu.Action(
                    enabled = state.hasSelection,
                    execute = state::copyFromContextMenu,
                )
            override val paste: TextContextMenu.Action? = null
            override val selectAll: TextContextMenu.Action? = null

            override fun selectWordAtPositionIfNotAlreadySelected(offset: Offset) {
                state.selectWordAtContainerPositionIfOutsideSelection(offset)
            }
        }
    }
    LocalTextContextMenu.current.Area(manager, contextMenuState, content)
}
