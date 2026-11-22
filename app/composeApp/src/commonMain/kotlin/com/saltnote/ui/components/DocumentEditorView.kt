package com.saltnote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixOff
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.DoNotTouch
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Preview
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.saltnote.bridge.RenderStrokeDto
import com.saltnote.bridge.SaltNoteClient
import com.saltnote.bridge.StrokeSample
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.platform.PlatformInkSurface
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
    modifier: Modifier = Modifier,
) {
    var showSplitPreview by remember { mutableStateOf(true) }
    val strings = LocalSaltStrings.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SaltAssistChip(
                onClick = onInsertInlineCanvas,
                label = { Text(strings.insertInlineCanvas) },
                leadingIcon = { Icon(Icons.Rounded.Draw, null, modifier = Modifier.size(18.dp)) },
            )
            SaltAssistChip(
                onClick = { onTriggerTextHalo("builtin.amber_expand", markdown.takeLast(320)) },
                label = { Text(strings.amberExpandChip) },
                leadingIcon = {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(SaltHaloAmber))
                },
            )
            SaltAssistChip(
                onClick = { onTriggerTextHalo("builtin.glacier_card", markdown.takeLast(320)) },
                label = { Text(strings.glacierCardChip) },
                leadingIcon = {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(SaltHaloGlacier))
                },
            )
            Spacer(Modifier.weight(1f))
            SaltFilterChip(
                selected = stylusOnlyMode,
                onClick = onToggleStylusOnly,
                label = { Text(strings.palmRejection) },
                leadingIcon = { Icon(Icons.Rounded.DoNotTouch, null, modifier = Modifier.size(16.dp)) },
            )
            SaltFilterChip(
                selected = showSplitPreview,
                onClick = { showSplitPreview = !showSplitPreview },
                label = { Text(strings.previewChip) },
                leadingIcon = { Icon(Icons.Rounded.Preview, null, modifier = Modifier.size(18.dp)) },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = markdown,
                onValueChange = onMarkdownChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text(strings.editorPlaceholder) },
                shape = MaterialTheme.shapes.medium,
            )

            if (showSplitPreview) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
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
                                        onStrokePersisted = onInlineStrokePersisted,
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
private fun InlineEmbeddedCanvasBlock(
    client: SaltNoteClient,
    pageId: String,
    canvasId: String,
    heightDp: Int,
    syncVersion: Long,
    stylusOnlyMode: Boolean,
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
