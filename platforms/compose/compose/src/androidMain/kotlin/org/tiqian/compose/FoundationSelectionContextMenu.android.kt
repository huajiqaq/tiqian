@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.tiqian.compose

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.TextContextMenuItems
import androidx.compose.foundation.text.contextmenu.internal.ProvideDefaultPlatformTextContextMenuProviders
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.ToolbarRequesterImpl
import androidx.compose.foundation.text.contextmenu.modifier.addTextContextMenuComponentsWithContext
import androidx.compose.foundation.text.contextmenu.modifier.showTextContextMenuOnSecondaryClick
import androidx.compose.foundation.text.contextmenu.modifier.textContextMenuToolbarHandler
import androidx.compose.foundation.text.contextmenu.modifier.translateRootToDestination
import androidx.compose.foundation.text.selection.addPlatformTextContextMenuItems
import androidx.compose.foundation.text.selection.PlatformSelectionBehaviors
import androidx.compose.foundation.text.selection.rememberPlatformSelectionBehaviors
import androidx.compose.foundation.text.selection.SelectedTextType
import androidx.compose.foundation.text.textItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Uses Foundation's current Android text-context-menu provider, which owns the platform ActionMode,
 * PROCESS_TEXT entries, positioning invalidation, and host-provided menu filters/components.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal actual fun FoundationSelectionContextMenuArea(
    state: CjkSelectionState,
    selectionActions: List<CjkSelectionMenuAction>,
    selectionMenuContent: (@Composable (CjkSelectionMenuContent) -> Unit)?,
    content: @Composable () -> Unit,
) {
    var platformSelectionBehaviors: PlatformSelectionBehaviors? = null
    if (ComposeFoundationFlags.isSmartSelectionEnabled) {
        platformSelectionBehaviors =
            rememberPlatformSelectionBehaviors(SelectedTextType.StaticText, localeList = null)
    }
    val toolbarRequester = remember(state) { ToolbarRequesterImpl() }
    // 宿主自绘菜单时用一个可观察标记代替平台菜单：show/hide 只切换该标记，
    // 平台菜单完全不安装，从而避免“系统菜单 + 自绘菜单”同时出现。
    var hostMenuVisible by remember(state) { mutableStateOf(false) }
    // 菜单显示瞬间冻结选中文本：自绘菜单抢到焦点后选区可能被清除，
    // 点击时再读会拿到空串。
    var frozenSelection by remember(state) { mutableStateOf("") }
    var frozenBounds by remember(state) { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val systemMenu = remember(state, toolbarRequester, selectionMenuContent) {
        CjkSystemContextMenu(
            show = {
                if (selectionMenuContent != null) {
                    // 按选区起止裁剪出精确文本：contextTextAndSelection 返回的
                    // AnnotatedString 是整段内容，TextRange 才是本次选中的区间。
                    frozenSelection = state.contextTextAndSelection()?.let { (text, range) ->
                        text.text.substring(
                            range.min.coerceIn(0, text.length),
                            range.max.coerceIn(0, text.length),
                        )
                    }.orEmpty()
                    frozenBounds = state.selectionContentRectInRoot()
                    hostMenuVisible = true
                } else {
                    toolbarRequester.show()
                }
            },
            hide = {
                if (selectionMenuContent != null) hostMenuVisible = false else toolbarRequester.hide()
            },
        )
    }
    DisposableEffect(state, systemMenu) {
        state.attachSystemContextMenu(systemMenu)
        onDispose { state.detachSystemContextMenu(systemMenu) }
    }

    val modifier = Modifier
        .addTextContextMenuComponentsWithContext { context ->
            val textAndSelection = state.contextTextAndSelection()
            addPlatformTextContextMenuItems(
                context = context,
                editable = false,
                text = textAndSelection?.first,
                selection = textAndSelection?.second,
                platformSelectionBehaviors = platformSelectionBehaviors,
            ) {
                separator()
                textItem(
                    resources = context.resources,
                    item = TextContextMenuItems.Copy,
                    enabled = state.hasSelection,
                ) {
                    state.copyFromContextMenu()
                    close()
                }
                textItem(
                    resources = context.resources,
                    item = TextContextMenuItems.SelectAll,
                    enabled = !state.isEntireContainerSelected(),
                ) {
                    state.selectAll()
                    if (!state.isTouchSelection) close()
                }
                // 宿主动作（如“喜欢/评论”）：排在系统项之后，点击时把当前选中文本交回宿主。
                if (selectionActions.isNotEmpty()) {
                    separator()
                    for (action in selectionActions) {
                        item(key = action.id, label = action.label) {
                            val selected = state.contextTextAndSelection()?.first?.text.orEmpty()
                            action.onInvoke(selected)
                            close()
                        }
                    }
                }
                separator()
            }
        }
        .showTextContextMenuOnSecondaryClick { clickLocation ->
            state.selectWordAtContainerPositionIfOutsideSelection(clickLocation)
            state.contextTextAndSelection()?.let { (text, selection) ->
                platformSelectionBehaviors?.onShowContextMenu(
                    text = text,
                    selection = selection,
                    secondaryClickLocation = clickLocation,
                )
            }
        }
        .textContextMenuToolbarHandler(
            requester = toolbarRequester,
            onShow = {
                state.contextTextAndSelection()?.let { (text, selection) ->
                    platformSelectionBehaviors?.onShowSelectionToolbar(text, selection)
                }
            },
            computeContentBounds = { destinationCoordinates ->
                val rootBounds = state.selectionContentRectInRoot()
                    ?: return@textContextMenuToolbarHandler null
                val containerCoordinates = state.contextMenuContainerCoordinates()
                    ?: return@textContextMenuToolbarHandler null
                translateRootToDestination(
                    rootContentBounds = rootBounds,
                    localCoordinates = containerCoordinates,
                    destinationCoordinates = destinationCoordinates,
                )
            },
        )

    ProvideDefaultPlatformTextContextMenuProviders(modifier) {
        content()
        // 选区可能已被正文点击等路径清除；此时不再渲染宿主菜单，
        // 否则非焦点弹窗会带着冻结的旧选区一直挂在页面上。
        if (selectionMenuContent != null && hostMenuVisible && state.hasSelection) {
            val menuContent = remember(state) {
                CjkSelectionMenuContent(
                    selectedText = { frozenSelection },
                    selectionBoundsInRoot = { frozenBounds },
                    onCopy = {
                        state.copyFromContextMenu()
                        hostMenuVisible = false
                    },
                    onSelectAll = { state.selectAll() },
                    onClose = {
                        // 自定义菜单：onClose 只藏菜单。清选区交给正文点击
                        // （foundationClearSelectionOnTap）或宿主动作；
                        // 若在这里清，弹窗 dismiss 吞掉的那次触摸会让
                        // "菜单开着时拖拽手柄"直接失效。
                        hostMenuVisible = false
                        frozenSelection = ""
                        frozenBounds = null
                    },
                    actions = selectionActions,
                )
            }
            selectionMenuContent(menuContent)
        }
    }
}
