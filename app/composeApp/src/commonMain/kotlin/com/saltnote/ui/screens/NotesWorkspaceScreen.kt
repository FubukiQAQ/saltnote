package com.saltnote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saltnote.ui.components.DocumentEditorView
import com.saltnote.ui.components.DualCanvasView
import com.saltnote.ui.components.SaltHaloCrystalOverlay
import com.saltnote.ui.platform.formatUtcTimestamp
import com.saltnote.ui.state.EditorViewMode
import com.saltnote.ui.state.SaltAppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesWorkspaceScreen(
    state: SaltAppState,
    isWideScreen: Boolean,
) {
    var showNewPageDialog by remember { mutableStateOf(false) }
    var showSidebarOnCompact by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxSize()) {
        if (isWideScreen || showSidebarOnCompact) {
            VaultSidebar(
                state = state,
                onRequestNewPage = { showNewPageDialog = true },
                onPageSelected = { pid ->
                    state.openPage(pid)
                    showSidebarOnCompact = false
                },
                modifier = Modifier
                    .width(if (isWideScreen) 296.dp else 280.dp)
                    .fillMaxHeight(),
            )
            VerticalDivider()
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val active = state.activePage
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!isWideScreen) {
                        IconButton(onClick = { showSidebarOnCompact = !showSidebarOnCompact }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "展开笔记列表")
                        }
                    }

                    if (active != null) {
                        var titleDraft by remember(active.meta.pageId, active.meta.title) {
                            mutableStateOf(active.meta.title)
                        }
                        BasicTextField(
                            value = titleDraft,
                            onValueChange = {
                                titleDraft = it
                                state.updatePageTitle(it)
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f),
                        )

                        FilterChip(
                            selected = active.meta.isPrivate,
                            onClick = { state.togglePagePrivate() },
                            label = { Text(if (active.meta.isPrivate) "私密" else "公开") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (active.meta.isPrivate) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                    contentDescription = "私密保护",
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )

                        SingleChoiceSegmentedButtonRow {
                            EditorViewMode.entries.forEachIndexed { idx, mode ->
                                SegmentedButton(
                                    selected = state.editorMode == mode,
                                    onClick = { state.editorMode = mode },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = idx,
                                        count = EditorViewMode.entries.size,
                                    ),
                                    icon = {
                                        Icon(
                                            imageVector = if (mode == EditorViewMode.Canvas) Icons.Rounded.Draw else Icons.Rounded.Description,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                ) {
                                    Text(if (mode == EditorViewMode.Canvas) "画布" else "文档")
                                }
                            }
                        }
                    } else {
                        Text("选择或新建一篇笔记", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            HorizontalDivider()

            Box(modifier = Modifier.fillMaxSize()) {
                if (active != null) {
                    when (state.editorMode) {
                        EditorViewMode.Canvas -> {
                            val canvasSnap = active.canvases.firstOrNull { it.canvasId == state.activeCanvasId }
                            DualCanvasView(
                                vaultPath = state.client.vaultPath,
                                strokes = state.canvasStrokes,
                                images = canvasSnap?.images.orEmpty(),
                                halos = active.halos,
                                selectedTool = state.selectedTool,
                                inkColor = state.inkColor,
                                strokeWidth = state.strokeWidth,
                                stylusOnlyMode = state.stylusOnlyMode,
                                panX = state.panX,
                                panY = state.panY,
                                zoom = state.zoom,
                                onSelectTool = { state.selectedTool = it },
                                onSelectColor = { state.inkColor = it },
                                onStrokeWidthChange = { state.strokeWidth = it },
                                onToggleStylusOnly = { state.stylusOnlyMode = !state.stylusOnlyMode },
                                onPanZoom = { dx, dy, f, px, py -> state.updatePanZoom(dx, dy, f, px, py) },
                                onResetViewport = { state.resetViewport() },
                                onStrokeCompleted = { pts -> state.onCanvasStrokeCompleted(state.activeCanvasId, pts) },
                                onEraserMove = { wx, wy -> state.onEraserMove(state.activeCanvasId, wx, wy) },
                                onInsertImageBytes = { bytes -> state.insertImageToActiveCanvas(bytes) },
                                onOpenHalo = { halo -> state.openPersistedHaloBubble(halo) },
                            )
                        }

                        EditorViewMode.Document -> {
                            DocumentEditorView(
                                client = state.client,
                                pageId = active.meta.pageId,
                                markdown = state.editableBody,
                                syncVersion = state.syncUiVersion,
                                stylusOnlyMode = state.stylusOnlyMode,
                                onToggleStylusOnly = { state.stylusOnlyMode = !state.stylusOnlyMode },
                                onMarkdownChange = { state.onBodyMarkdownEdited(it) },
                                onInsertInlineCanvas = { state.insertInlineCanvasBlock() },
                                onTriggerTextHalo = { presetId, excerpt -> state.triggerTextHalo(presetId, excerpt) },
                                onInlineStrokeCompleted = { cid, pts ->
                                    state.onCanvasStrokeCompleted(cid, pts, forcedBrush = "pen")
                                },
                            )
                        }
                    }
                }

                state.activeCrystal?.let { crystal ->
                    SaltHaloCrystalOverlay(
                        bubble = crystal,
                        onToggleCollapse = { state.toggleCrystalCollapsed() },
                        onCloseAndCancel = { state.closeCrystalBubble() },
                        onFuseIntoBody = { state.fuseActiveCrystalIntoBody() },
                        onImportCards = { state.importCrystalDraftCardsToFsrs() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 72.dp, end = 16.dp),
                    )
                }
            }
        }
    }

    if (showNewPageDialog) {
        NewPageDialog(
            onDismiss = { showNewPageDialog = false },
            onCreate = { title, mode, isPrivate ->
                state.createNewPage(title, mode, isPrivate)
                showNewPageDialog = false
            },
        )
    }
}

@Composable
private fun VaultSidebar(
    state: SaltAppState,
    onRequestNewPage: () -> Unit,
    onPageSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("全部笔记", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${state.pages.size} 篇",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onRequestNewPage,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建")
            }
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { state.updateSearchQuery(it) },
            placeholder = { Text("搜索笔记…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { state.updateSearchQuery("") }) {
                        Icon(Icons.Rounded.Close, "清空搜索", modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = state.modeFilter == null,
                onClick = { state.modeFilter = null },
                label = { Text("全部") },
            )
            FilterChip(
                selected = state.modeFilter == "study",
                onClick = { state.modeFilter = "study" },
                label = { Text("学习") },
            )
            FilterChip(
                selected = state.modeFilter == "journal",
                onClick = { state.modeFilter = "journal" },
                label = { Text("手账") },
            )
        }

        if (state.searchQuery.isNotBlank()) {
            Text(
                text = "找到 ${state.searchHits.size} 条结果",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(state.searchHits, key = { it.pageId }) { hit ->
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPageSelected(hit.pageId) },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = hit.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (hit.isPrivate) {
                                    Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(14.dp))
                                }
                            }
                            Text(
                                text = hit.snippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        } else {
            val filtered = state.pages.filter {
                state.modeFilter == null || it.meta.mode == state.modeFilter
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(filtered, key = { it.meta.pageId }) { item ->
                    val isSelected = state.activePage?.meta?.pageId == item.meta.pageId
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPageSelected(item.meta.pageId) },
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            },
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = item.meta.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (item.meta.isPrivate) {
                                    Icon(
                                        Icons.Rounded.Lock,
                                        contentDescription = "私密",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            if (item.snippet.isNotBlank()) {
                                Text(
                                    text = item.snippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (item.meta.mode == "journal") "手账" else "学习",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = formatUtcTimestamp(item.meta.updatedAtUtc).takeLast(5),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewPageDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, mode: String, isPrivate: Boolean) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("study") }
    var isPrivate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建笔记") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    placeholder = { Text("输入标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == "study",
                        onClick = { mode = "study" },
                        label = { Text("学习笔记") },
                    )
                    FilterChip(
                        selected = mode == "journal",
                        onClick = { mode = "journal" },
                        label = { Text("手账画布") },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isPrivate, onCheckedChange = { isPrivate = it })
                    Text("设为私密笔记", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { onCreate(title, mode, isPrivate) }) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
