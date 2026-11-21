package com.saltnote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixOff
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.DoNotTouch
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.saltnote.bridge.CanvasImageNodeDto
import com.saltnote.bridge.HaloAnnotationDto
import com.saltnote.bridge.RenderStrokeDto
import com.saltnote.bridge.StrokeSample
import com.saltnote.ui.platform.PlatformInkSurface
import com.saltnote.ui.platform.loadImageBitmapFromVault
import com.saltnote.ui.platform.pickLocalImageBytes
import com.saltnote.ui.theme.SaltHaloAmber
import com.saltnote.ui.theme.SaltHaloGlacier
import com.saltnote.ui.theme.SaltHaloMineral
import kotlin.math.roundToInt

private val DefaultInkPalette = listOf(
    Color(0xFF191C1E),
    Color(0xFF1A657A),
    Color(0xFFC62828),
    Color(0xFF2E7D32),
    Color(0xFF6A1B9A),
)

@Composable
fun DualCanvasView(
    vaultPath: String,
    strokes: List<RenderStrokeDto>,
    images: List<CanvasImageNodeDto>,
    halos: List<HaloAnnotationDto>,
    selectedTool: String,
    inkColor: Color,
    strokeWidth: Float,
    stylusOnlyMode: Boolean,
    panX: Float,
    panY: Float,
    zoom: Float,
    onSelectTool: (String) -> Unit,
    onSelectColor: (Color) -> Unit,
    onStrokeWidthChange: (Float) -> Unit,
    onToggleStylusOnly: () -> Unit,
    onPanZoom: (Float, Float, Float, Float, Float) -> Unit,
    onResetViewport: () -> Unit,
    onStrokeCompleted: (List<StrokeSample>) -> Unit,
    onEraserMove: (Float, Float) -> Unit,
    onInsertImageBytes: (ByteArray) -> Unit,
    onOpenHalo: (HaloAnnotationDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeToolColor = when (selectedTool) {
        "halo_amber" -> SaltHaloAmber
        "halo_glacier" -> SaltHaloGlacier
        "halo_mineral" -> SaltHaloMineral
        "highlighter" -> if (inkColor == DefaultInkPalette[0]) SaltHaloAmber else inkColor
        else -> inkColor
    }

    val decodedImages = remember(vaultPath, images) {
        images.filter { !it.deleted }.mapNotNull { node ->
            loadImageBitmapFromVault(vaultPath, node.assetRelPath)?.let { bmp -> node to bmp }
        }
    }

    val dotColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val paperBg = MaterialTheme.colorScheme.surfaceContainerLowest

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(paperBg)
            .clipToBounds(),
    ) {
        // Layer 1: WYSIWYG Native-DPI Smooth Dry-Ink & Placed Images
        Canvas(modifier = Modifier.fillMaxSize()) {
            val spacing = 32f * zoom
            if (spacing >= 10f) {
                val startX = ((panX % spacing) + spacing) % spacing
                val startY = ((panY % spacing) + spacing) % spacing
                var gx = startX
                while (gx < size.width) {
                    var gy = startY
                    while (gy < size.height) {
                        drawCircle(color = dotColor, radius = 1.25f, center = Offset(gx, gy))
                        gy += spacing
                    }
                    gx += spacing
                }
            }

            val viewportMinX = -panX / zoom - 64f
            val viewportMinY = -panY / zoom - 64f
            val viewportMaxX = (size.width - panX) / zoom + 64f
            val viewportMaxY = (size.height - panY) / zoom + 64f

            withTransform({
                translate(left = panX, top = panY)
                scale(scaleX = zoom, scaleY = zoom, pivot = Offset.Zero)
            }) {
                for ((node, bmp) in decodedImages) {
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(node.x.roundToInt(), node.y.roundToInt()),
                        dstSize = IntSize(
                            node.width.roundToInt().coerceAtLeast(1),
                            node.height.roundToInt().coerceAtLeast(1),
                        ),
                    )
                }

                drawWysiwygStrokes(
                    strokes = strokes,
                    viewportMinX = viewportMinX,
                    viewportMinY = viewportMinY,
                    viewportMaxX = viewportMaxX,
                    viewportMaxY = viewportMaxY,
                )
            }
        }

        // Layer 2: Platform low-latency wet-ink layer with palm rejection
        PlatformInkSurface(
            modifier = Modifier.fillMaxSize(),
            toolKind = selectedTool,
            strokeColor = activeToolColor,
            strokeWidth = strokeWidth,
            panX = panX,
            panY = panY,
            zoom = zoom,
            stylusOnlyMode = stylusOnlyMode,
            allowTouchScrollPassThrough = false,
            onPanZoom = onPanZoom,
            onStrokeCompleted = onStrokeCompleted,
            onEraserMove = onEraserMove,
        )

        // Layer 3: Anchored Salt Crystal Pins
        halos.forEach { halo ->
            val bbox = halo.anchorBbox ?: return@forEach
            val sx = (bbox.maxX * zoom + panX).roundToInt()
            val sy = (bbox.minY * zoom + panY).roundToInt()
            val pinColor = when (halo.presetId) {
                "builtin.amber_expand" -> SaltHaloAmber
                "builtin.glacier_card" -> SaltHaloGlacier
                "builtin.mineral_ocr" -> SaltHaloMineral
                else -> MaterialTheme.colorScheme.primary
            }
            Surface(
                modifier = Modifier
                    .offset { IntOffset(sx.coerceIn(12, 1600), sy.coerceIn(72, 1000)) }
                    .clickable { onOpenHalo(halo) },
                shape = CircleShape,
                color = pinColor,
                shadowElevation = 3.dp,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = "打开批注",
                    tint = Color.White,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(16.dp),
                )
            }
        }

        // Layer 4: Material Design 3 Floating Tool Dock
        ElevatedCard(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp, start = 12.dp, end = 12.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ToolToggleIcon(
                    selected = selectedTool == "pen",
                    onClick = { onSelectTool("pen") },
                    icon = Icons.Rounded.Create,
                    description = "钢笔",
                )
                ToolToggleIcon(
                    selected = selectedTool == "highlighter",
                    onClick = { onSelectTool("highlighter") },
                    icon = Icons.Rounded.BorderColor,
                    description = "荧光笔",
                )
                ToolToggleIcon(
                    selected = selectedTool == "eraser",
                    onClick = { onSelectTool("eraser") },
                    icon = Icons.Rounded.AutoFixOff,
                    description = "橡皮擦",
                )
                ToolToggleIcon(
                    selected = selectedTool == "pan",
                    onClick = { onSelectTool("pan") },
                    icon = Icons.Rounded.OpenWith,
                    description = "移动画布",
                )

                // Stylus-only palm rejection toggle
                Surface(
                    onClick = onToggleStylusOnly,
                    shape = RoundedCornerShape(18.dp),
                    color = if (stylusOnlyMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (stylusOnlyMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DoNotTouch,
                            contentDescription = "防误触",
                            tint = if (stylusOnlyMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "防误触",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (stylusOnlyMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                // 3-Color Salt Halo Command Pens
                HaloPenButton(
                    label = "扩充",
                    color = SaltHaloAmber,
                    selected = selectedTool == "halo_amber",
                    onClick = { onSelectTool("halo_amber") },
                )
                HaloPenButton(
                    label = "制卡",
                    color = SaltHaloGlacier,
                    selected = selectedTool == "halo_glacier",
                    onClick = { onSelectTool("halo_glacier") },
                )
                HaloPenButton(
                    label = "OCR",
                    color = SaltHaloMineral,
                    selected = selectedTool == "halo_mineral",
                    onClick = { onSelectTool("halo_mineral") },
                )

                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                // Ink Color Swatches
                DefaultInkPalette.forEach { swatch ->
                    val chosen = inkColor == swatch && !selectedTool.startsWith("halo_")
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(swatch)
                            .border(
                                width = if (chosen) 2.5.dp else 1.dp,
                                color = if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = CircleShape,
                            )
                            .clickable {
                                onSelectColor(swatch)
                                if (selectedTool.startsWith("halo_") || selectedTool == "eraser" || selectedTool == "pan") {
                                    onSelectTool("pen")
                                }
                            },
                    )
                }

                Spacer(Modifier.width(4.dp))

                IconButton(
                    onClick = { onStrokeWidthChange((strokeWidth - 1f).coerceAtLeast(1.5f)) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Rounded.Remove, "细", modifier = Modifier.size(16.dp))
                }
                Text(
                    text = "${strokeWidth.roundToInt()}px",
                    style = MaterialTheme.typography.labelSmall,
                )
                IconButton(
                    onClick = { onStrokeWidthChange((strokeWidth + 1f).coerceAtMost(14f)) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Rounded.Add, "粗", modifier = Modifier.size(16.dp))
                }

                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                IconButton(
                    onClick = {
                        pickLocalImageBytes()?.let { onInsertImageBytes(it) }
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(Icons.Rounded.Image, "插入图片")
                }
            }
        }

        // Layer 5: Bottom-Right Zoom Controls
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = { onPanZoom(0f, 0f, 1f / 1.2f, 400f, 300f) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Rounded.Remove, "缩小", modifier = Modifier.size(16.dp))
                }
                Text(
                    text = "${(zoom * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { onResetViewport() },
                )
                IconButton(
                    onClick = { onPanZoom(0f, 0f, 1.2f, 400f, 300f) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Rounded.Add, "放大", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onResetViewport, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.CenterFocusStrong, "重置视角", modifier = Modifier.size(16.dp))
                }
            }
        }

        if (selectedTool.startsWith("halo_")) {
            val hint = when (selectedTool) {
                "halo_amber" -> "琥珀黄：圈选内容以扩充解释"
                "halo_glacier" -> "冰川蓝：圈选内容以生成闪卡"
                else -> "矿石灰：圈选笔迹以识别文字"
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp),
                shape = RoundedCornerShape(16.dp),
                color = activeToolColor.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, activeToolColor),
            ) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolToggleIcon(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
) {
    FilledIconToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        modifier = Modifier.size(36.dp),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun HaloPenButton(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) color.copy(alpha = 0.28f) else Color.Transparent,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, color.copy(alpha = if (selected) 1f else 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
