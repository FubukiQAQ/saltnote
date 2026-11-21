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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.saltnote.bridge.RenderStrokeDto
import com.saltnote.bridge.SaltNoteClient
import com.saltnote.bridge.StrokeSample
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
    onInlineStrokeCompleted: (canvasId: String, points: List<StrokeSample>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSplitPreview by remember { mutableStateOf(true) }

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
            AssistChip(
                onClick = onInsertInlineCanvas,
                label = { Text("插入行内画布") },
                leadingIcon = { Icon(Icons.Rounded.Draw, null, modifier = Modifier.size(18.dp)) },
            )
            AssistChip(
                onClick = { onTriggerTextHalo("builtin.amber_expand", markdown.takeLast(320)) },
                label = { Text("琥珀扩充") },
                leadingIcon = {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(SaltHaloAmber))
                },
            )
            AssistChip(
                onClick = { onTriggerTextHalo("builtin.glacier_card", markdown.takeLast(320)) },
                label = { Text("冰川制卡") },
                leadingIcon = {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(SaltHaloGlacier))
                },
            )
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = stylusOnlyMode,
                onClick = onToggleStylusOnly,
                label = { Text("防误触") },
                leadingIcon = { Icon(Icons.Rounded.DoNotTouch, null, modifier = Modifier.size(16.dp)) },
            )
            FilterChip(
                selected = showSplitPreview,
                onClick = { showSplitPreview = !showSplitPreview },
                label = { Text("预览") },
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
                placeholder = { Text("开始写作…") },
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
                                        onStrokeCompleted = { pts ->
                                            onInlineStrokeCompleted(seg.canvasId, pts)
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
private fun InlineEmbeddedCanvasBlock(
    client: SaltNoteClient,
    pageId: String,
    canvasId: String,
    heightDp: Int,
    syncVersion: Long,
    stylusOnlyMode: Boolean,
    onStrokeCompleted: (List<StrokeSample>) -> Unit,
) {
    val strokes = remember(pageId, canvasId) { mutableStateListOf<RenderStrokeDto>() }
    var inlineTool by remember(pageId, canvasId) { mutableStateOf("pen") }
    var refreshKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val inkColor = MaterialTheme.colorScheme.onSurface

    LaunchedEffect(pageId, canvasId, refreshKey, syncVersion) {
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
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "行内画布块 · $canvasId",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                FilledIconToggleButton(
                    checked = inlineTool == "pen",
                    onCheckedChange = { inlineTool = "pen" },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Rounded.Create, contentDescription = "画笔", modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(4.dp))
                FilledIconToggleButton(
                    checked = inlineTool == "eraser",
                    onCheckedChange = { inlineTool = "eraser" },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Rounded.AutoFixOff, contentDescription = "橡皮擦", modifier = Modifier.size(15.dp))
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
                        onStrokeCompleted(pts)
                        scope.launch {
                            // Brief refresh after Rust ingests the stroke
                            kotlinx.coroutines.delay(50)
                            refreshKey++
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
