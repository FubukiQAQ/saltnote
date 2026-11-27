package com.saltnote.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saltnote.bridge.PageSummaryDto
import com.saltnote.ui.components.CompactCanvasPreview
import com.saltnote.ui.components.DocumentEditorView
import com.saltnote.ui.components.DualCanvasView
import com.saltnote.ui.components.SaltAssistChip
import com.saltnote.ui.components.SaltFilterChip
import com.saltnote.ui.components.SaltHaloCrystalOverlay
import com.saltnote.ui.components.SaltTrayDrawer
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.platform.formatUtcTimestamp
import com.saltnote.ui.state.EditorViewMode
import com.saltnote.ui.state.SaltAppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesWorkspaceScreen(
    state: SaltAppState,
    isWideScreen: Boolean,
) {
    val strings = LocalSaltStrings.current
    var showNewPageDialog by remember { mutableStateOf(false) }
    var showSidebarOnCompact by remember { mutableStateOf(false) }
    var showTagManagerDialog by remember { mutableStateOf(false) }
    var showHeaderOverflow by remember { mutableStateOf(false) }
    var pendingDeletePage by remember { mutableStateOf<Pair<String, String>?>(null) } // (pageId, title)

    val allVaultTags = remember(state.pages.toList()) {
        state.pages.flatMap { it.meta.tags }.distinct().sorted()
    }
    val unprocessedInboxCount = remember(state.inboxItems.toList()) {
        state.inboxItems.count { it.state == "unprocessed" }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        LaunchedEffect(isWideScreen, maxWidth) {
            if (isWideScreen && !state.sidebarAutoResolved) {
                state.showVaultSidebar = maxWidth >= 1080.dp
                state.sidebarAutoResolved = true
            }
        }

        val showSidebar = !state.isImmersiveEditorMode &&
            if (isWideScreen) state.showVaultSidebar else showSidebarOnCompact

        Row(modifier = Modifier.fillMaxSize()) {
            if (showSidebar) {
                VaultSidebar(
                    state = state,
                    allTags = allVaultTags,
                    onRequestNewPage = { showNewPageDialog = true },
                    onRequestDeletePage = { summary ->
                        pendingDeletePage = summary.meta.pageId to summary.meta.title
                    },
                    onPageSelected = { pid ->
                        state.openPage(pid)
                        if (!isWideScreen) showSidebarOnCompact = false
                    },
                    onChildNavigated = {
                        if (!isWideScreen) showSidebarOnCompact = false
                    },
                    modifier = Modifier
                        .width(if (isWideScreen) 292.dp else 276.dp)
                        .fillMaxHeight(),
                )
                VerticalDivider()
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val active = state.activePage
                if (!state.isImmersiveEditorMode) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            IconButton(
                                onClick = {
                                    if (isWideScreen) {
                                        state.showVaultSidebar = !state.showVaultSidebar
                                    } else {
                                        showSidebarOnCompact = !showSidebarOnCompact
                                    }
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                val sidebarVisible = if (isWideScreen) state.showVaultSidebar else showSidebarOnCompact
                                Icon(
                                    imageVector = if (sidebarVisible) Icons.AutoMirrored.Rounded.MenuOpen else Icons.Rounded.Menu,
                                    contentDescription = strings.toggleSidebarDesc,
                                    modifier = Modifier.size(20.dp),
                                )
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
                                    textStyle = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.weight(1f),
                                )

                                if (active.meta.isPrivate) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = strings.privateLabel,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }

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
                                                    modifier = Modifier.size(15.dp),
                                                )
                                            },
                                        ) {
                                            Text(
                                                text = if (mode == EditorViewMode.Canvas) strings.modeCanvas else strings.modeDocument,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        if (state.editorMode == EditorViewMode.Canvas) {
                                            state.isCanvasFocusMode = !state.isCanvasFocusMode
                                        } else {
                                            state.isDocumentLargeScreenMode = !state.isDocumentLargeScreenMode
                                        }
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Fullscreen,
                                        contentDescription = if (state.editorMode == EditorViewMode.Canvas) {
                                            strings.focusMode
                                        } else {
                                            strings.largeScreenMode
                                        },
                                        modifier = Modifier.size(20.dp),
                                    )
                                }

                                if (isWideScreen) {
                                    IconButton(
                                        onClick = { state.showSaltTray = !state.showSaltTray },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        if (unprocessedInboxCount > 0) {
                                            BadgedBox(
                                                badge = {
                                                    Badge { Text(unprocessedInboxCount.toString()) }
                                                },
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Inbox,
                                                    contentDescription = strings.saltTrayTitle,
                                                    tint = if (state.showSaltTray) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.Inbox,
                                                contentDescription = strings.saltTrayTitle,
                                                tint = if (state.showSaltTray) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }

                                Box {
                                    IconButton(
                                        onClick = { showHeaderOverflow = true },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MoreVert,
                                            contentDescription = strings.moreActionsDesc,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showHeaderOverflow,
                                        onDismissRequest = { showHeaderOverflow = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(if (active.meta.isPrivate) strings.privateLabel else strings.publicLabel)
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (active.meta.isPrivate) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            },
                                            trailingIcon = if (active.meta.isPrivate) {
                                                { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp)) }
                                            } else {
                                                null
                                            },
                                            onClick = {
                                                showHeaderOverflow = false
                                                state.togglePagePrivate()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                val tagSuffix = if (active.meta.tags.isNotEmpty()) " (${active.meta.tags.size})" else ""
                                                Text("${strings.tagsLabel}$tagSuffix")
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.LocalOffer, null, modifier = Modifier.size(18.dp))
                                            },
                                            onClick = {
                                                showHeaderOverflow = false
                                                showTagManagerDialog = true
                                            },
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = {
                                                Text(strings.deleteNote, color = MaterialTheme.colorScheme.error)
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Rounded.DeleteOutline,
                                                    null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            },
                                            onClick = {
                                                showHeaderOverflow = false
                                                pendingDeletePage = active.meta.pageId to active.meta.title
                                            },
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = strings.selectOrCreateNote,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (active != null) {
                        when (state.editorMode) {
                            EditorViewMode.Canvas -> {
                                val canvasSnap = active.canvases.firstOrNull { it.canvasId == state.activeCanvasId }
                                if (!isWideScreen) {
                                    CompactCanvasPreview(
                                        vaultPath = state.client.vaultPath,
                                        strokes = state.canvasStrokes,
                                        images = canvasSnap?.images.orEmpty(),
                                        halos = active.halos,
                                        panX = state.panX,
                                        panY = state.panY,
                                        zoom = state.zoom,
                                        onPanZoom = { dx, dy, f, px, py -> state.updatePanZoom(dx, dy, f, px, py) },
                                        onResetViewport = { state.resetViewport() },
                                        onOpenHalo = { halo -> state.openPersistedHaloBubble(halo) },
                                        activeCanvasId = state.activeCanvasId,
                                        canvases = active.canvases,
                                        onSwitchCanvas = { state.switchCanvas(it) },
                                        activeHaloId = state.activeCrystal?.persistedHalo?.haloId,
                                    )
                                } else {
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
                                        onSelectTool = { state.selectTool(it) },
                                        onStrokeWidthChange = { state.strokeWidth = it },
                                        onToggleStylusOnly = { state.stylusOnlyMode = !state.stylusOnlyMode },
                                        onPanZoom = { dx, dy, f, px, py -> state.updatePanZoom(dx, dy, f, px, py) },
                                        onResetViewport = { state.resetViewport() },
                                        onStrokeCompleted = { pts -> state.onCanvasStrokeCompleted(state.activeCanvasId, pts) },
                                        onEraserMove = { wx, wy -> state.onEraserMove(state.activeCanvasId, wx, wy) },
                                        onInsertImageBytes = { bytes -> state.insertImageToActiveCanvas(bytes) },
                                        onOpenHalo = { halo -> state.openPersistedHaloBubble(halo) },
                                        penPalette = state.penPalette.toList(),
                                        selectedColorSlotIndex = state.selectedColorSlotIndex,
                                        recentCustomColors = state.recentCustomColors.toList(),
                                        onSelectColorSlot = { state.selectPenColorSlot(it) },
                                        onUpdateColorSlot = { idx, c -> state.updatePenColorSlot(idx, c) },
                                        onResetPaletteDefaults = { state.resetPenPaletteDefaults() },
                                        activeCanvasId = state.activeCanvasId,
                                        canvases = active.canvases,
                                        onSwitchCanvas = { state.switchCanvas(it) },
                                        onCreateCanvas = { state.createCanvas() },
                                        onRenameCanvas = { cid, title -> state.renameCanvas(cid, title) },
                                        onDeleteCanvas = { cid -> state.deleteCanvas(cid) },
                                        isDockCollapsed = state.isCanvasDockCollapsed,
                                        onToggleDockCollapsed = { state.isCanvasDockCollapsed = !state.isCanvasDockCollapsed },
                                        isFocusMode = state.isCanvasFocusMode,
                                        onToggleFocusMode = { state.isCanvasFocusMode = !state.isCanvasFocusMode },
                                        activeHaloId = state.activeCrystal?.persistedHalo?.haloId,
                                        selectedImageId = state.selectedImageId,
                                        onSelectImage = { state.selectedImageId = it },
                                        onMoveHaloPin = { haloId, px, py -> state.moveHaloPin(haloId, px, py) },
                                        onUpdateImageTransform = { imageId, x, y, w, h, deleted ->
                                            state.updateCanvasImageTransform(imageId, x, y, w, h, deleted)
                                        },
                                    )
                                }
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
                                    onInlineStrokePersisted = { state.notifyInlineStrokePersisted() },
                                    documents = active.documents,
                                    activeDocId = state.activeDocId,
                                    onSwitchDoc = { state.switchDocument(it) },
                                    onCreateDoc = { state.createDocument() },
                                    onRenameDoc = { did, title -> state.renameDocument(did, title) },
                                    onDeleteDoc = { did -> state.deleteDocument(did) },
                                    layoutMode = state.documentLayoutMode,
                                    onLayoutModeChange = { state.documentLayoutMode = it },
                                    isLargeScreenMode = state.isDocumentLargeScreenMode,
                                    onToggleLargeScreenMode = { state.isDocumentLargeScreenMode = !state.isDocumentLargeScreenMode },
                                    isCompact = !isWideScreen,
                                )
                            }
                        }
                    }

                    state.activeCrystal?.let { crystal ->
                        SaltHaloCrystalOverlay(
                            bubble = crystal,
                            onToggleCollapse = { state.toggleCrystalCollapsed() },
                            onDeleteHalo = { state.deleteActiveCrystalHalo() },
                            onCloseAndCancel = { state.closeCrystalBubble() },
                            onFuseIntoBody = { state.fuseActiveCrystalIntoBody() },
                            onImportCards = { state.importCrystalDraftCardsToFsrs() },
                            onRemoveDraftCard = { idx -> state.removeCrystalDraftCard(idx) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 72.dp, end = 16.dp),
                        )
                    }
                }
            }

            if (!state.isImmersiveEditorMode && isWideScreen && state.showSaltTray) {
                VerticalDivider()
                SaltTrayDrawer(
                    state = state,
                    onClose = { state.showSaltTray = false },
                    modifier = Modifier
                        .width(304.dp)
                        .fillMaxHeight(),
                )
            }
        }
    }

    if (showNewPageDialog) {
        NewPageDialog(
            onDismiss = { showNewPageDialog = false },
            onCreate = { title, mode, isPrivate, tags ->
                state.createNewPage(title, mode, isPrivate, tags)
                showNewPageDialog = false
            },
        )
    }

    if (showTagManagerDialog && state.activePage != null) {
        val currentTags = state.activePage!!.meta.tags
        TagManagerDialog(
            existingTags = currentTags,
            suggestions = allVaultTags.filterNot { it in currentTags },
            onDismiss = { showTagManagerDialog = false },
            onAddTag = { tag ->
                state.updatePageTags((currentTags + tag).distinct())
            },
            onRemoveTag = { tag ->
                state.updatePageTags(currentTags.filterNot { it == tag })
            },
        )
    }

    pendingDeletePage?.let { (pid, title) ->
        AlertDialog(
            onDismissRequest = { pendingDeletePage = null },
            title = { Text(strings.deleteNote) },
            text = { Text("${strings.deleteNoteConfirmPrefix}$title${strings.deleteNoteConfirmSuffix}") },
            confirmButton = {
                Button(
                    onClick = {
                        state.deletePage(pid)
                        pendingDeletePage = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(strings.confirmDelete) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePage = null }) { Text(strings.cancel) }
            },
        )
    }
}

@Composable
private fun VaultSidebar(
    state: SaltAppState,
    allTags: List<String>,
    onRequestNewPage: () -> Unit,
    onRequestDeletePage: (PageSummaryDto) -> Unit,
    onPageSelected: (String) -> Unit,
    onChildNavigated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalSaltStrings.current
    val expandedPageIds = remember { mutableStateMapOf<String, Boolean>() }

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
                Text(strings.allNotes, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${state.pages.size} ${strings.notesCountSuffix}",
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
                Text(strings.newNote)
            }
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { state.updateSearchQuery(it) },
            placeholder = { Text(strings.searchPlaceholder) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { state.updateSearchQuery("") }) {
                        Icon(Icons.Rounded.Close, strings.clearSearch, modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SaltFilterChip(
                selected = state.modeFilter == null,
                onClick = { state.modeFilter = null },
                label = { Text(strings.filterAll) },
            )
            SaltFilterChip(
                selected = state.modeFilter == "study",
                onClick = { state.modeFilter = "study" },
                label = { Text(strings.filterStudy) },
            )
            SaltFilterChip(
                selected = state.modeFilter == "journal",
                onClick = { state.modeFilter = "journal" },
                label = { Text(strings.filterJournal) },
            )
        }

        if (allTags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.tagFilter != null) {
                    SaltFilterChip(
                        selected = true,
                        onClick = { state.tagFilter = null },
                        label = { Text("#${state.tagFilter}") },
                        trailingIcon = { Icon(Icons.Rounded.Close, strings.clearTagFilter, modifier = Modifier.size(14.dp)) },
                    )
                }
                allTags.filter { it != state.tagFilter }.forEach { tag ->
                    SaltFilterChip(
                        selected = false,
                        onClick = { state.tagFilter = tag },
                        label = { Text("#$tag") },
                    )
                }
            }
        }

        if (state.searchQuery.isNotBlank()) {
            Text(
                text = "${strings.searchResultsPrefix}${state.searchHits.size}${strings.searchResultsSuffix}",
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
            val filtered = state.pages.filter { item ->
                val matchMode = state.modeFilter == null || item.meta.mode == state.modeFilter
                val matchTag = state.tagFilter == null || state.tagFilter in item.meta.tags
                matchMode && matchTag
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(filtered, key = { it.meta.pageId }) { item ->
                    val isSelected = state.activePage?.meta?.pageId == item.meta.pageId
                    val defaultExpanded = isSelected && (item.canvases.size > 1 || item.documents.size > 1)
                    val isExpanded = expandedPageIds[item.meta.pageId] ?: defaultExpanded

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
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                IconButton(
                                    onClick = { expandedPageIds[item.meta.pageId] = !isExpanded },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Text(
                                    text = item.meta.title.ifBlank { strings.studyNote },
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (item.meta.isPrivate) {
                                    Icon(
                                        Icons.Rounded.Lock,
                                        contentDescription = strings.privateLabel,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                                IconButton(
                                    onClick = { onRequestDeletePage(item) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = strings.deleteNote,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }

                            if (!isExpanded && item.snippet.isNotBlank()) {
                                Text(
                                    text = item.snippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 28.dp),
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 28.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val tagsPreview = item.meta.tags.take(2).joinToString(" ") { "#$it" }
                                Text(
                                    text = buildString {
                                        append(if (item.meta.mode == "journal") strings.filterJournal else strings.filterStudy)
                                        if (tagsPreview.isNotBlank()) {
                                            append(" · ")
                                            append(tagsPreview)
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = formatUtcTimestamp(item.meta.updatedAtUtc).takeLast(5),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            if (isExpanded) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                                // Canvases child group
                                SidebarChildGroupHeader(
                                    title = strings.canvasesSection,
                                    showAdd = isSelected,
                                    addDesc = strings.newCanvas,
                                    onAdd = { state.createCanvas() },
                                )
                                item.canvases.forEach { c ->
                                    val isChildActive = isSelected &&
                                        state.editorMode == EditorViewMode.Canvas &&
                                        state.activeCanvasId == c.canvasId
                                    SidebarChildItemRow(
                                        icon = Icons.Rounded.Draw,
                                        label = c.title.ifBlank {
                                            if (c.canvasId == "main") strings.defaultMainCanvasTitle else c.canvasId
                                        },
                                        selected = isChildActive,
                                        onClick = {
                                            state.openPage(
                                                pageId = item.meta.pageId,
                                                initialMode = EditorViewMode.Canvas,
                                                targetCanvasId = c.canvasId,
                                            )
                                            onChildNavigated()
                                        },
                                    )
                                }

                                // Documents child group
                                SidebarChildGroupHeader(
                                    title = strings.documentsSection,
                                    showAdd = isSelected,
                                    addDesc = strings.newDocument,
                                    onAdd = { state.createDocument() },
                                )
                                item.documents.forEach { d ->
                                    val isChildActive = isSelected &&
                                        state.editorMode == EditorViewMode.Document &&
                                        state.activeDocId == d.docId
                                    SidebarChildItemRow(
                                        icon = Icons.Rounded.Description,
                                        label = d.title.ifBlank {
                                            if (d.docId == "main") strings.defaultMainDocTitle else d.docId
                                        },
                                        selected = isChildActive,
                                        onClick = {
                                            state.openPage(
                                                pageId = item.meta.pageId,
                                                initialMode = EditorViewMode.Document,
                                                targetDocId = d.docId,
                                            )
                                            onChildNavigated()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarChildGroupHeader(
    title: String,
    showAdd: Boolean,
    addDesc: String,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        if (showAdd) {
            IconButton(
                onClick = onAdd,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = addDesc, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun SidebarChildItemRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun NewPageDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, mode: String, isPrivate: Boolean, tags: List<String>) -> Unit,
) {
    val strings = LocalSaltStrings.current
    var title by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("study") }
    var isPrivate by remember { mutableStateOf(false) }
    var tagsRaw by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.newNote) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(strings.titleLabel) },
                    placeholder = { Text(strings.titlePlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tagsRaw,
                    onValueChange = { tagsRaw = it },
                    label = { Text(strings.tagsLabel) },
                    placeholder = { Text(strings.tagPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SaltFilterChip(
                        selected = mode == "study",
                        onClick = { mode = "study" },
                        label = { Text(strings.studyNote) },
                    )
                    SaltFilterChip(
                        selected = mode == "journal",
                        onClick = { mode = "journal" },
                        label = { Text(strings.journalCanvas) },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isPrivate, onCheckedChange = { isPrivate = it })
                    Text(strings.privateLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    val parsedTags = tagsRaw
                        .split(',', '，', ' ')
                        .map { it.trim().removePrefix("#") }
                        .filter { it.isNotEmpty() }
                    onCreate(title, mode, isPrivate, parsedTags)
                },
            ) {
                Text(strings.create)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagManagerDialog(
    existingTags: List<String>,
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    val strings = LocalSaltStrings.current
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.tagsLabel) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (existingTags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        existingTags.forEach { tag ->
                            SaltFilterChip(
                                selected = true,
                                onClick = { onRemoveTag(tag) },
                                label = { Text("#$tag") },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "${strings.removeTagDesc} $tag",
                                        modifier = Modifier.size(14.dp),
                                    )
                                },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text(strings.addTag) },
                        placeholder = { Text(strings.tagPlaceholder) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    val cleaned = input.trim().removePrefix("#")
                    FilledTonalButton(
                        onClick = {
                            if (cleaned.isNotEmpty() && cleaned !in existingTags) {
                                onAddTag(cleaned)
                                input = ""
                            }
                        },
                        enabled = cleaned.isNotEmpty() && cleaned !in existingTags,
                    ) { Text(strings.add) }
                }
                if (suggestions.isNotEmpty()) {
                    Text(
                        text = strings.existingTagsLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.take(12).forEach { s ->
                            SaltAssistChip(
                                onClick = { onAddTag(s) },
                                label = { Text("#$s") },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strings.done) }
        },
    )
}
