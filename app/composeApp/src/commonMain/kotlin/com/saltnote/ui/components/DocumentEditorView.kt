package com.saltnote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixOff
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoNotTouch
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Preview
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saltnote.bridge.PageDocumentSnapshotDto
import com.saltnote.bridge.RenderStrokeDto
import com.saltnote.bridge.SaltNoteClient
import com.saltnote.bridge.StrokeSample
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.platform.PlatformInkSurface
import com.saltnote.ui.state.DocumentLayoutMode
import com.saltnote.ui.theme.SaltHaloAmber
import com.saltnote.ui.theme.SaltHaloGlacier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface MarkdownSegment {
    data class TextBlock(val lines: List<String>) : MarkdownSegment
    data class InlineCanvasBlock(val canvasId: String, val heightDp: Int) : MarkdownSegment
}

private fun parseDocumentSegments(markdown: String): List<MarkdownSegment> {
    val result = mutableListOf<MarkdownSegment>()
    val lines = markdown.lines()
    val currentText = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line.startsWith("```salt-canvas")) {
            if (currentText.isNotEmpty()) {
                result.add(MarkdownSegment.TextBlock(currentText.toList()))
                currentText.clear()
            }
            val blockLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                blockLines.add(lines[i])
                i++
            }
            val rawJson = blockLines.joinToString("")
            val idMatch = Regex("\"canvas_id\"\\s*:\\s*\"([^\"]+)\"").find(rawJson)
            val hMatch = Regex("\"height\"\\s*:\\s*(\\d+)").find(rawJson)
            val cid = idMatch?.groupValues?.getOrNull(1) ?: "inline-default"
            val height = hMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(140, 480) ?: 240
            result.add(MarkdownSegment.InlineCanvasBlock(cid, height))
        } else {
            currentText.add(lines[i])
        }
        i++
    }
    if (currentText.isNotEmpty()) {
        result.add(MarkdownSegment.TextBlock(currentText.toList()))
    }
    return result
}

@Composable
fun DocumentEditorView(
    client: SaltNoteClient,
    pageId: String,
    markdown: String,
    syncVersion: Long,
    stylusOnlyMode: Boolean,
    onToggleStylusOnly: () -> Unit,
    onMarkdownChange: (String) -> Unit,
    onInsertInlineCanvas: () -> Unit,
    onTriggerTextHalo: (presetId: String, excerpt: String) -> Unit,
    onInlineStrokePersisted: () -> Unit,
    documents: List<PageDocumentSnapshotDto> = emptyList(),
    activeDocId: String = "main",
    onSwitchDoc: (String) -> Unit = {},
    onCreateDoc: () -> Unit = {},
    onRenameDoc: (String, String) -> Unit = { _, _ -> },
    onDeleteDoc: (String) -> Unit = {},
    layoutMode: DocumentLayoutMode = DocumentLayoutMode.Split,
    onLayoutModeChange: (DocumentLayoutMode) -> Unit = {},
    isLargeScreenMode: Boolean = false,
    onToggleLargeScreenMode: () -> Unit = {},
    isCompact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val strings = LocalSaltStrings.current
    val visibleDocs = remember(documents, activeDocId) {
        val activeOnly = documents.filter { !it.deleted }
        activeOnly.ifEmpty { listOf(PageDocumentSnapshotDto(docId = activeDocId, title = "")) }
    }
    var renamingDoc by remember { mutableStateOf<PageDocumentSnapshotDto?>(null) }
    var deletingDoc by remember { mutableStateOf<PageDocumentSnapshotDto?>(null) }
    val escapeFocusRequester = remember { FocusRequester() }

    val effectiveLayout = if (isCompact && layoutMode == DocumentLayoutMode.Split) {
        DocumentLayoutMode.PreviewOnly
    } else {
        layoutMode
    }

    LaunchedEffect(isLargeScreenMode, effectiveLayout) {
        if (isLargeScreenMode && effectiveLayout == DocumentLayoutMode.PreviewOnly) {
            runCatching { escapeFocusRequester.requestFocus() }
        }
    }

    fun docDisplayTitle(doc: PageDocumentSnapshotDto): String =
        doc.title.ifBlank {
            if (doc.docId == "main") strings.defaultMainDocTitle else doc.docId
        }

    val activeDoc = visibleDocs.firstOrNull { it.docId == activeDocId } ?: visibleDocs.first()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .focusRequester(escapeFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (isLargeScreenMode &&
                    keyEvent.type == KeyEventType.KeyDown &&
                    keyEvent.key == Key.Escape
                ) {
                    onToggleLargeScreenMode()
                    true
                } else {
                    false
                }
            },
    ) {
        if (isLargeScreenMode) {
            // Document Large-Screen Mode: full-bleed centered page or 50/50 split + corner HUD
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 60.dp, start = 20.dp, end = 20.dp, bottom = 18.dp),
            ) {
                DocumentContentBody(
                    client = client,
                    pageId = pageId,
                    markdown = markdown,
                    syncVersion = syncVersion,
                    stylusOnlyMode = stylusOnlyMode,
                    isCompact = isCompact,
                    effectiveLayout = effectiveLayout,
                    centeredMaxWidth = effectiveLayout != DocumentLayoutMode.Split,
                    onMarkdownChange = onMarkdownChange,
                    onInlineStrokePersisted = onInlineStrokePersisted,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Floating Top-Right HUD Pill
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Doc Selector Dropdown inside HUD
                    var docMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        Surface(
                            onClick = { docMenuExpanded = true },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = docDisplayTitle(activeDoc),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 140.dp),
                                )
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                        DropdownMenu(
                            expanded = docMenuExpanded,
                            onDismissRequest = { docMenuExpanded = false },
                        ) {
                            visibleDocs.forEach { doc ->
                                val isCurrent = doc.docId == activeDocId
                                DropdownMenuItem(
                                    text = { Text(docDisplayTitle(doc)) },
                                    leadingIcon = if (isCurrent) {
                                        { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp)) }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        docMenuExpanded = false
                                        onSwitchDoc(doc.docId)
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(strings.newDocument) },
                                leadingIcon = { Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    docMenuExpanded = false
                                    onCreateDoc()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(strings.renameDocument) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    docMenuExpanded = false
                                    renamingDoc = activeDoc
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(strings.deleteDocument) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(16.dp)) },
                                enabled = visibleDocs.size > 1,
                                onClick = {
                                    docMenuExpanded = false
                                    deletingDoc = activeDoc
                                },
                            )
                        }
                    }

                    DocumentLayoutModePill(
                        effectiveLayout = effectiveLayout,
                        isCompact = isCompact,
                        onSelectLayout = onLayoutModeChange,
                    )

                    DocumentHiddenToolsMenuButton(
                        stylusOnlyMode = stylusOnlyMode,
                        onToggleStylusOnly = onToggleStylusOnly,
                        onInsertInlineCanvas = onInsertInlineCanvas,
                        onAmberExpand = { onTriggerTextHalo("builtin.amber_expand", markdown.takeLast(320)) },
                        onGlacierCards = { onTriggerTextHalo("builtin.glacier_card", markdown.takeLast(320)) },
                    )

                    Surface(
                        onClick = onToggleLargeScreenMode,
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FullscreenExit,
                                contentDescription = strings.exitLargeScreenMode,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(17.dp),
                            )
                            if (!isCompact) {
                                Text(
                                    text = strings.escToExitHint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (isCompact) 10.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Single slim toolbar row: Multi-Doc Tabs + Layout Switcher + Hidden Tools ⋮ + Large Screen
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DocumentTabStrip(
                        documents = visibleDocs,
                        activeDocId = activeDocId,
                        docTitle = ::docDisplayTitle,
                        onSwitchDoc = onSwitchDoc,
                        onCreateDoc = onCreateDoc,
                        onRequestRename = { renamingDoc = it },
                        onRequestDelete = { deletingDoc = it },
                        modifier = Modifier.weight(1f),
                    )

                    DocumentLayoutModePill(
                        effectiveLayout = effectiveLayout,
                        isCompact = isCompact,
                        onSelectLayout = onLayoutModeChange,
                    )

                    DocumentHiddenToolsMenuButton(
                        stylusOnlyMode = stylusOnlyMode,
                        onToggleStylusOnly = onToggleStylusOnly,
                        onInsertInlineCanvas = onInsertInlineCanvas,
                        onAmberExpand = { onTriggerTextHalo("builtin.amber_expand", markdown.takeLast(320)) },
                        onGlacierCards = { onTriggerTextHalo("builtin.glacier_card", markdown.takeLast(320)) },
                    )

                    IconButton(
                        onClick = onToggleLargeScreenMode,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Fullscreen,
                            contentDescription = strings.largeScreenMode,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                DocumentContentBody(
                    client = client,
                    pageId = pageId,
                    markdown = markdown,
                    syncVersion = syncVersion,
                    stylusOnlyMode = stylusOnlyMode,
                    isCompact = isCompact,
                    effectiveLayout = effectiveLayout,
                    centeredMaxWidth = false,
                    onMarkdownChange = onMarkdownChange,
                    onInlineStrokePersisted = onInlineStrokePersisted,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }

    renamingDoc?.let { target ->
        SaltRenameItemDialog(
            title = strings.renameDocument,
            initialName = docDisplayTitle(target),
            onConfirm = { nextName ->
                onRenameDoc(target.docId, nextName)
                renamingDoc = null
            },
            onDismiss = { renamingDoc = null },
        )
    }

    deletingDoc?.let { target ->
        SaltConfirmDeleteDialog(
            title = strings.deleteDocument,
            message = strings.deleteDocumentConfirm,
            onConfirm = {
                onDeleteDoc(target.docId)
                deletingDoc = null
            },
            onDismiss = { deletingDoc = null },
        )
    }
}

@Composable
private fun DocumentContentBody(
    client: SaltNoteClient,
    pageId: String,
    markdown: String,
    syncVersion: Long,
    stylusOnlyMode: Boolean,
    isCompact: Boolean,
    effectiveLayout: DocumentLayoutMode,
    centeredMaxWidth: Boolean,
    onMarkdownChange: (String) -> Unit,
    onInlineStrokePersisted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalSaltStrings.current
    val editorTextStyle = if (isCompact) {
        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    } else {
        MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
    }

    when (effectiveLayout) {
        DocumentLayoutMode.EditOnly -> {
            Box(
                modifier = modifier,
                contentAlignment = if (centeredMaxWidth) Alignment.TopCenter else Alignment.TopStart,
            ) {
                OutlinedTextField(
                    value = markdown,
                    onValueChange = onMarkdownChange,
                    modifier = (if (centeredMaxWidth) Modifier.widthIn(max = 920.dp) else Modifier)
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    textStyle = editorTextStyle,
                    placeholder = { Text(strings.editorPlaceholder) },
                    shape = MaterialTheme.shapes.medium,
                )
            }
        }
        DocumentLayoutMode.PreviewOnly -> {
            Box(
                modifier = modifier,
                contentAlignment = if (centeredMaxWidth) Alignment.TopCenter else Alignment.TopStart,
            ) {
                DocumentPreviewPane(
                    client = client,
                    pageId = pageId,
                    markdown = markdown,
                    syncVersion = syncVersion,
                    stylusOnlyMode = stylusOnlyMode,
                    readOnlyInlineCanvas = isCompact,
                    onInlineStrokePersisted = onInlineStrokePersisted,
                    modifier = (if (centeredMaxWidth) Modifier.widthIn(max = 920.dp) else Modifier)
                        .fillMaxWidth()
                        .fillMaxHeight(),
                )
            }
        }
        DocumentLayoutMode.Split -> {
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = markdown,
                    onValueChange = onMarkdownChange,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    textStyle = editorTextStyle,
                    placeholder = { Text(strings.editorPlaceholder) },
                    shape = MaterialTheme.shapes.medium,
                )
                DocumentPreviewPane(
                    client = client,
                    pageId = pageId,
                    markdown = markdown,
                    syncVersion = syncVersion,
                    stylusOnlyMode = stylusOnlyMode,
                    readOnlyInlineCanvas = false,
                    onInlineStrokePersisted = onInlineStrokePersisted,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun DocumentTabStrip(
    documents: List<PageDocumentSnapshotDto>,
    activeDocId: String,
    docTitle: (PageDocumentSnapshotDto) -> String,
    onSwitchDoc: (String) -> Unit,
    onCreateDoc: () -> Unit,
    onRequestRename: (PageDocumentSnapshotDto) -> Unit,
    onRequestDelete: (PageDocumentSnapshotDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalSaltStrings.current
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        documents.forEach { doc ->
            val selected = doc.docId == activeDocId
            var menuOpen by remember(doc.docId) { mutableStateOf(false) }
            Box {
                Surface(
                    onClick = {
                        if (selected) {
                            menuOpen = true
                        } else {
                            onSwitchDoc(doc.docId)
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = docTitle(doc),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(strings.renameDocument) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            menuOpen = false
                            onRequestRename(doc)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(strings.deleteDocument) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(16.dp)) },
                        enabled = documents.size > 1,
                        onClick = {
                            menuOpen = false
                            onRequestDelete(doc)
                        },
                    )
                }
            }
        }
        IconButton(
            onClick = onCreateDoc,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = strings.newDocument,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DocumentLayoutModePill(
    effectiveLayout: DocumentLayoutMode,
    isCompact: Boolean,
    onSelectLayout: (DocumentLayoutMode) -> Unit,
) {
    val strings = LocalSaltStrings.current
    val options = if (isCompact) {
        listOf(
            DocumentLayoutMode.EditOnly to Pair(Icons.Rounded.Create, strings.layoutEditOnly),
            DocumentLayoutMode.PreviewOnly to Pair(Icons.Rounded.Preview, strings.layoutPreviewOnly),
        )
    } else {
        listOf(
            DocumentLayoutMode.EditOnly to Pair(Icons.Rounded.Create, strings.layoutEditOnly),
            DocumentLayoutMode.Split to Pair(Icons.Rounded.ViewColumn, strings.layoutSplit),
            DocumentLayoutMode.PreviewOnly to Pair(Icons.Rounded.Preview, strings.layoutPreviewOnly),
        )
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { (mode, iconAndDesc) ->
                val selected = effectiveLayout == mode
                Surface(
                    onClick = { onSelectLayout(mode) },
                    shape = RoundedCornerShape(11.dp),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = iconAndDesc.first,
                            contentDescription = iconAndDesc.second,
                            tint = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentHiddenToolsMenuButton(
    stylusOnlyMode: Boolean,
    onToggleStylusOnly: () -> Unit,
    onInsertInlineCanvas: () -> Unit,
    onAmberExpand: () -> Unit,
    onGlacierCards: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(34.dp),
        ) {
            Icon(Icons.Rounded.MoreVert, contentDescription = strings.moreActionsDesc, modifier = Modifier.size(19.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(strings.insertInlineCanvas) },
                leadingIcon = { Icon(Icons.Rounded.Draw, null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    expanded = false
                    onInsertInlineCanvas()
                },
            )
            DropdownMenuItem(
                text = { Text(strings.amberExpandChip) },
                leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(SaltHaloAmber)) },
                onClick = {
                    expanded = false
                    onAmberExpand()
                },
            )
            DropdownMenuItem(
                text = { Text(strings.glacierCardChip) },
                leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(SaltHaloGlacier)) },
                onClick = {
                    expanded = false
                    onGlacierCards()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(strings.palmRejection) },
                leadingIcon = { Icon(Icons.Rounded.DoNotTouch, null, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (stylusOnlyMode) {
                    { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
                onClick = {
                    expanded = false
                    onToggleStylusOnly()
                },
            )
        }
    }
}

@Composable
private fun DocumentPreviewPane(
    client: SaltNoteClient,
    pageId: String,
    markdown: String,
    syncVersion: Long,
    stylusOnlyMode: Boolean,
    readOnlyInlineCanvas: Boolean,
    onInlineStrokePersisted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        val segments = remember(markdown) { parseDocumentSegments(markdown) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            segments.forEach { seg ->
                when (seg) {
                    is MarkdownSegment.TextBlock -> {
                        MarkdownLinesPreview(seg.lines)
                    }
                    is MarkdownSegment.InlineCanvasBlock -> {
                        InlineEmbeddedCanvasBlock(
                            client = client,
                            pageId = pageId,
                            canvasId = seg.canvasId,
                            heightDp = seg.heightDp,
                            syncVersion = syncVersion,
                            stylusOnlyMode = stylusOnlyMode,
                            readOnly = readOnlyInlineCanvas,
                            onStrokePersisted = onInlineStrokePersisted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineEmbeddedCanvasBlock(
    client: SaltNoteClient,
    pageId: String,
    canvasId: String,
    heightDp: Int,
    syncVersion: Long,
    stylusOnlyMode: Boolean,
    readOnly: Boolean,
    onStrokePersisted: () -> Unit,
) {
    val strokes = remember(pageId, canvasId) { mutableStateListOf<RenderStrokeDto>() }
    var inlineTool by remember(pageId, canvasId) { mutableStateOf("pen") }
    var pendingSeq by remember(pageId, canvasId) { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val inkColor = MaterialTheme.colorScheme.onSurface

    LaunchedEffect(pageId, canvasId, syncVersion) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                client.getCanvasStrokes(pageId, canvasId, -10_000f, -10_000f, 10_000f, 10_000f)
            }.getOrDefault(emptyList())
        }
        strokes.clear()
        strokes.addAll(loaded)
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Clean header: ONLY block label + identifier + Pen / Eraser toggle buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Draw,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                val strings = LocalSaltStrings.current
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${strings.inlineCanvasHeader} · $canvasId",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (!readOnly) {
                    FilledIconToggleButton(
                        checked = inlineTool == "pen",
                        onCheckedChange = { inlineTool = "pen" },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(Icons.Rounded.Create, contentDescription = strings.toolPen, modifier = Modifier.size(15.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    FilledIconToggleButton(
                        checked = inlineTool == "eraser",
                        onCheckedChange = { inlineTool = "eraser" },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(Icons.Rounded.AutoFixOff, contentDescription = strings.toolEraser, modifier = Modifier.size(15.dp))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .clipToBounds(),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawWysiwygStrokes(
                        strokes = strokes,
                        viewportMinX = -64f,
                        viewportMinY = -64f,
                        viewportMaxX = size.width + 64f,
                        viewportMaxY = size.height + 64f,
                    )
                }

                if (!readOnly) {
                    PlatformInkSurface(
                        modifier = Modifier.fillMaxSize(),
                        toolKind = inlineTool,
                        strokeColor = inkColor,
                        strokeWidth = 3f,
                        panX = 0f,
                        panY = 0f,
                        zoom = 1f,
                        stylusOnlyMode = stylusOnlyMode,
                        allowTouchScrollPassThrough = true,
                        onPanZoom = { _, _, _, _, _ -> },
                        onStrokeCompleted = { pts ->
                            // Synchronously append optimistic dry stroke BEFORE wet ink clears -> zero blink!
                            val tempId = "pending-inline-${++pendingSeq}"
                            val argb = inkColor.toArgb().toLong() and 0xFFFFFFFFL
                            val optimistic = StrokeGeometry.buildOptimisticRenderStroke(
                                tempId = tempId,
                                brush = "pen",
                                colorArgb = argb,
                                baseWidth = 3f,
                                points = pts,
                            )
                            strokes.add(optimistic)
                            scope.launch {
                                val persisted = withContext(Dispatchers.IO) {
                                    runCatching {
                                        client.ingestRawStroke(pageId, canvasId, "pen", argb, 3f, pts)
                                    }.getOrNull()
                                }
                                if (persisted != null) {
                                    val idx = strokes.indexOfFirst { it.strokeId == tempId }
                                    if (idx >= 0) {
                                        strokes[idx] = persisted.renderStroke
                                    } else {
                                        strokes.add(persisted.renderStroke)
                                    }
                                    onStrokePersisted()
                                }
                            }
                        },
                        onEraserMove = { wx, wy ->
                            scope.launch {
                                val erased = withContext(Dispatchers.IO) {
                                    runCatching {
                                        client.eraseStrokesAt(pageId, canvasId, wx, wy, 18f)
                                    }.getOrDefault(emptyList())
                                }
                                if (erased.isNotEmpty()) {
                                    strokes.removeAll { it.strokeId in erased }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
