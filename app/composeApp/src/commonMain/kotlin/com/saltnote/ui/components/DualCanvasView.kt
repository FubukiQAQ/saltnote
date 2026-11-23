package com.saltnote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixOff
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.saltnote.bridge.CanvasImageNodeDto
import com.saltnote.bridge.HaloAnnotationDto
import com.saltnote.bridge.RenderStrokeDto
import com.saltnote.bridge.StrokeSample
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.platform.PlatformInkSurface
import com.saltnote.ui.platform.loadImageBitmapFromVault
import com.saltnote.ui.platform.pickLocalImageBytes
import com.saltnote.ui.theme.SaltHaloAmber
import com.saltnote.ui.theme.SaltHaloGlacier
import com.saltnote.ui.theme.SaltHaloMineral
import kotlin.math.abs
import kotlin.math.roundToInt

private data class ImageWorldRect(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

private enum class ResizeCorner {
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
}

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
    activeCanvasId: String = "main",
    activeHaloId: String? = null,
    selectedImageId: String? = null,
    onSelectImage: (String?) -> Unit = {},
    onMoveHaloPin: (String, Float, Float) -> Unit = { _, _, _ -> },
    onUpdateImageTransform: (String, Float, Float, Float, Float, Boolean) -> Unit = { _, _, _, _, _, _ -> },
) {
    val strings = LocalSaltStrings.current
    val density = LocalDensity.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var activeGestureImageId by remember { mutableStateOf<String?>(null) }
    var previewImageRect by remember { mutableStateOf<ImageWorldRect?>(null) }

    val activeToolColor = when (selectedTool) {
        "halo_amber" -> SaltHaloAmber
        "halo_glacier" -> SaltHaloGlacier
        "halo_mineral" -> SaltHaloMineral
        "highlighter" -> if (inkColor == DefaultInkPalette[0]) SaltHaloAmber else inkColor
        else -> inkColor
    }

    val visibleImages = remember(images) {
        images.filter { !it.deleted }
    }
    val decodedImages = remember(vaultPath, visibleImages) {
        visibleImages.mapNotNull { node ->
            loadImageBitmapFromVault(vaultPath, node.assetRelPath)?.let { bmp -> node to bmp }
        }
    }
    val visibleHalos = remember(halos, activeCanvasId) {
        halos.filter { it.canvasId == null || it.canvasId == activeCanvasId }
    }
    val activeHalo = remember(visibleHalos, activeHaloId) {
        if (activeHaloId == null) null else visibleHalos.firstOrNull { it.haloId == activeHaloId }
    }

    val dotColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val paperBg = MaterialTheme.colorScheme.surfaceContainerLowest
    val defaultPrimaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(paperBg)
            .clipToBounds()
            .onSizeChanged { viewportSize = it },
    ) {
        // Layer 1: WYSIWYG Native-DPI Smooth Dry-Ink, Placed Images & Active Halo Anchor Region
        CanvasDryContentLayer(
            strokes = strokes,
            decodedImages = decodedImages,
            activeGestureImageId = activeGestureImageId,
            previewImageRect = previewImageRect,
            activeHalo = activeHalo,
            panX = panX,
            panY = panY,
            zoom = zoom,
            dotColor = dotColor,
            defaultPrimaryColor = defaultPrimaryColor,
        )

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

        // Layer 2b: Interactive Image Move & Corner-Resize Overlay when Image Edit tool is active
        if (selectedTool == "select") {
            ImageManipulationOverlay(
                images = visibleImages,
                selectedImageId = selectedImageId,
                activeGestureImageId = activeGestureImageId,
                previewImageRect = previewImageRect,
                panX = panX,
                panY = panY,
                zoom = zoom,
                viewportSize = viewportSize,
                onSelectImage = onSelectImage,
                onPan = { dx, dy -> onPanZoom(dx, dy, 1f, 0f, 0f) },
                onGesturePreview = { imgId, rect ->
                    activeGestureImageId = imgId
                    previewImageRect = rect
                },
                onCommitTransform = { imageId, rect ->
                    activeGestureImageId = null
                    previewImageRect = null
                    onUpdateImageTransform(imageId, rect.x, rect.y, rect.w, rect.h, false)
                },
                onDeleteImage = { node ->
                    activeGestureImageId = null
                    previewImageRect = null
                    onUpdateImageTransform(node.imageId, node.x, node.y, node.width, node.height, true)
                },
                deleteImageDesc = strings.deleteImageDesc,
            )
        }

        // Layer 3: Draggable Anchored Salt Crystal Pins
        visibleHalos.forEach { halo ->
            val bbox = halo.anchorBbox ?: return@forEach
            val pinWorldX = halo.pinX ?: (bbox.maxX + 10f)
            val pinWorldY = halo.pinY ?: bbox.minY
            val pinColor = haloPinColor(halo.presetId, defaultPrimaryColor)
            val isActivePin = halo.haloId == activeHaloId

            DraggableHaloPin(
                halo = halo,
                worldX = pinWorldX,
                worldY = pinWorldY,
                panX = panX,
                panY = panY,
                zoom = zoom,
                viewportSize = viewportSize,
                pinColor = pinColor,
                isActive = isActivePin,
                openDescription = strings.openHaloPinDesc,
                pinHalfPx = with(density) { 14.dp.toPx() },
                onOpen = { onOpenHalo(halo) },
                onMoveCommit = { newWorldX, newWorldY ->
                    onMoveHaloPin(halo.haloId, newWorldX, newWorldY)
                },
            )
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
                    description = strings.toolPen,
                )
                ToolToggleIcon(
                    selected = selectedTool == "highlighter",
                    onClick = { onSelectTool("highlighter") },
                    icon = Icons.Rounded.BorderColor,
                    description = strings.toolHighlighter,
                )
                ToolToggleIcon(
                    selected = selectedTool == "eraser",
                    onClick = { onSelectTool("eraser") },
                    icon = Icons.Rounded.AutoFixOff,
                    description = strings.toolEraser,
                )
                ToolToggleIcon(
                    selected = selectedTool == "select",
                    onClick = { onSelectTool(if (selectedTool == "select") "pen" else "select") },
                    icon = Icons.Rounded.AspectRatio,
                    description = strings.toolSelectImage,
                )
                ToolToggleIcon(
                    selected = selectedTool == "pan",
                    onClick = { onSelectTool("pan") },
                    icon = Icons.Rounded.OpenWith,
                    description = strings.toolPan,
                )

                // Stylus-only palm rejection toggle
                Surface(
                    onClick = onToggleStylusOnly,
                    shape = RoundedCornerShape(18.dp),
                    color = if (stylusOnlyMode) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    },
                    shadowElevation = 0.dp,
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
                            contentDescription = strings.palmRejection,
                            tint = if (stylusOnlyMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = strings.palmRejection,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (stylusOnlyMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                // 3-Color Salt Halo Command Pens
                HaloPenButton(
                    label = strings.haloAmberShort,
                    color = SaltHaloAmber,
                    selected = selectedTool == "halo_amber",
                    onClick = { onSelectTool("halo_amber") },
                )
                HaloPenButton(
                    label = strings.haloGlacierShort,
                    color = SaltHaloGlacier,
                    selected = selectedTool == "halo_glacier",
                    onClick = { onSelectTool("halo_glacier") },
                )
                HaloPenButton(
                    label = strings.haloMineralShort,
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
                                if (selectedTool.startsWith("halo_") || selectedTool == "eraser" || selectedTool == "pan" || selectedTool == "select") {
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
                    Icon(Icons.Rounded.Remove, strings.thinnerDesc, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = "${strokeWidth.roundToInt()}px",
                    style = MaterialTheme.typography.labelSmall,
                )
                IconButton(
                    onClick = { onStrokeWidthChange((strokeWidth + 1f).coerceAtMost(14f)) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Rounded.Add, strings.thickerDesc, modifier = Modifier.size(16.dp))
                }

                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                IconButton(
                    onClick = {
                        pickLocalImageBytes()?.let { onInsertImageBytes(it) }
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(Icons.Rounded.Image, strings.insertImageDesc)
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
                    Icon(Icons.Rounded.Remove, strings.zoomOutDesc, modifier = Modifier.size(16.dp))
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
                    Icon(Icons.Rounded.Add, strings.zoomInDesc, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onResetViewport, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.CenterFocusStrong, strings.resetViewportDesc, modifier = Modifier.size(16.dp))
                }
            }
        }

        if (selectedTool.startsWith("halo_")) {
            val hint = when (selectedTool) {
                "halo_amber" -> strings.haloAmberHint
                "halo_glacier" -> strings.haloGlacierHint
                else -> strings.haloMineralHint
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
        } else if (selectedTool == "select") {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = strings.imageEditHint,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    TextButton(
                        onClick = {
                            onSelectImage(null)
                            onSelectTool("pen")
                        },
                    ) {
                        Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(strings.done)
                    }
                }
            }
        }
    }
}

/**
 * Compact (<600dp) phone HD read-only canvas preview rendered purely via Compose [Canvas]
 * without a `SurfaceView` overlay, eliminating Android surface Z-order glitches while
 * supporting pinch-zoom, panning, and clickable Salt Halo crystal pins.
 */
@Composable
fun CompactCanvasPreview(
    vaultPath: String,
    strokes: List<RenderStrokeDto>,
    images: List<CanvasImageNodeDto>,
    halos: List<HaloAnnotationDto>,
    panX: Float,
    panY: Float,
    zoom: Float,
    onPanZoom: (Float, Float, Float, Float, Float) -> Unit,
    onResetViewport: () -> Unit,
    onOpenHalo: (HaloAnnotationDto) -> Unit,
    modifier: Modifier = Modifier,
    activeCanvasId: String = "main",
    activeHaloId: String? = null,
) {
    val strings = LocalSaltStrings.current
    val density = LocalDensity.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val decodedImages = remember(vaultPath, images) {
        images.filter { !it.deleted }.mapNotNull { node ->
            loadImageBitmapFromVault(vaultPath, node.assetRelPath)?.let { bmp -> node to bmp }
        }
    }
    val visibleHalos = remember(halos, activeCanvasId) {
        halos.filter { it.canvasId == null || it.canvasId == activeCanvasId }
    }
    val activeHalo = remember(visibleHalos, activeHaloId) {
        if (activeHaloId == null) null else visibleHalos.firstOrNull { it.haloId == activeHaloId }
    }

    val dotColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val paperBg = MaterialTheme.colorScheme.surfaceContainerLowest
    val defaultPrimaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(paperBg)
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .pointerInput(activeCanvasId) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    onPanZoom(pan.x, pan.y, gestureZoom, centroid.x, centroid.y)
                }
            },
    ) {
        CanvasDryContentLayer(
            strokes = strokes,
            decodedImages = decodedImages,
            activeGestureImageId = null,
            previewImageRect = null,
            activeHalo = activeHalo,
            panX = panX,
            panY = panY,
            zoom = zoom,
            dotColor = dotColor,
            defaultPrimaryColor = defaultPrimaryColor,
        )

        visibleHalos.forEach { halo ->
            val bbox = halo.anchorBbox ?: return@forEach
            val pinWorldX = halo.pinX ?: (bbox.maxX + 10f)
            val pinWorldY = halo.pinY ?: bbox.minY
            DraggableHaloPin(
                halo = halo,
                worldX = pinWorldX,
                worldY = pinWorldY,
                panX = panX,
                panY = panY,
                zoom = zoom,
                viewportSize = viewportSize,
                pinColor = haloPinColor(halo.presetId, defaultPrimaryColor),
                isActive = halo.haloId == activeHaloId,
                openDescription = strings.openHaloPinDesc,
                pinHalfPx = with(density) { 14.dp.toPx() },
                onOpen = { onOpenHalo(halo) },
                onMoveCommit = { _, _ -> },
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
            tonalElevation = 1.dp,
        ) {
            Text(
                text = strings.compactCanvasPreviewHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${(zoom * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clickable { onResetViewport() }
                        .padding(horizontal = 6.dp),
                )
                IconButton(onClick = onResetViewport, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.CenterFocusStrong, strings.resetViewportDesc, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun CanvasDryContentLayer(
    strokes: List<RenderStrokeDto>,
    decodedImages: List<Pair<CanvasImageNodeDto, ImageBitmap>>,
    activeGestureImageId: String?,
    previewImageRect: ImageWorldRect?,
    activeHalo: HaloAnnotationDto?,
    panX: Float,
    panY: Float,
    zoom: Float,
    dotColor: Color,
    defaultPrimaryColor: Color,
) {
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

        for ((node, bmp) in decodedImages) {
            val rect = if (node.imageId == activeGestureImageId && previewImageRect != null) {
                previewImageRect
            } else {
                ImageWorldRect(node.x, node.y, node.width, node.height)
            }
            val leftPx = (rect.x * zoom + panX).roundToInt()
            val topPx = (rect.y * zoom + panY).roundToInt()
            val rightPx = ((rect.x + rect.w) * zoom + panX).roundToInt()
            val bottomPx = ((rect.y + rect.h) * zoom + panY).roundToInt()
            drawImage(
                image = bmp,
                dstOffset = IntOffset(leftPx, topPx),
                dstSize = IntSize(
                    (rightPx - leftPx).coerceAtLeast(1),
                    (bottomPx - topPx).coerceAtLeast(1),
                ),
            )
        }

        withTransform({
            translate(left = panX, top = panY)
            scale(scaleX = zoom, scaleY = zoom, pivot = Offset.Zero)
        }) {
            drawWysiwygStrokes(
                strokes = strokes,
                viewportMinX = viewportMinX,
                viewportMinY = viewportMinY,
                viewportMaxX = viewportMaxX,
                viewportMaxY = viewportMaxY,
            )

            val bbox = activeHalo?.anchorBbox
            if (activeHalo != null && bbox != null) {
                val accent = haloPinColor(activeHalo.presetId, defaultPrimaryColor)
                val safeZ = zoom.coerceAtLeast(0.25f)
                val pad = 8f / safeZ
                val left = bbox.minX - pad
                val top = bbox.minY - pad
                val width = (bbox.maxX - bbox.minX).coerceAtLeast(16f) + pad * 2f
                val height = (bbox.maxY - bbox.minY).coerceAtLeast(16f) + pad * 2f
                val corner = CornerRadius(10f / safeZ, 10f / safeZ)

                drawRoundRect(
                    color = accent.copy(alpha = 0.14f),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.85f),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = corner,
                    style = Stroke(
                        width = 2f / safeZ,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f / safeZ, 6f / safeZ)),
                    ),
                )

                val pinWorldX = activeHalo.pinX ?: (bbox.maxX + 10f)
                val pinWorldY = activeHalo.pinY ?: bbox.minY
                val anchorX = pinWorldX.coerceIn(left, left + width)
                val anchorY = pinWorldY.coerceIn(top, top + height)
                val dx = pinWorldX - anchorX
                val dy = pinWorldY - anchorY
                if (dx * dx + dy * dy > 4f) {
                    drawLine(
                        color = accent.copy(alpha = 0.72f),
                        start = Offset(anchorX, anchorY),
                        end = Offset(pinWorldX, pinWorldY),
                        strokeWidth = 1.6f / safeZ,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f / safeZ, 5f / safeZ)),
                    )
                }
            }
        }
    }
}

private fun haloPinColor(presetId: String, defaultColor: Color): Color = when (presetId) {
    "builtin.amber_expand" -> SaltHaloAmber
    "builtin.glacier_card" -> SaltHaloGlacier
    "builtin.mineral_ocr" -> SaltHaloMineral
    else -> defaultColor
}

@Composable
private fun DraggableHaloPin(
    halo: HaloAnnotationDto,
    worldX: Float,
    worldY: Float,
    panX: Float,
    panY: Float,
    zoom: Float,
    viewportSize: IntSize,
    pinColor: Color,
    isActive: Boolean,
    openDescription: String,
    pinHalfPx: Float,
    onOpen: () -> Unit,
    onMoveCommit: (Float, Float) -> Unit,
) {
    var dragDeltaPx by remember(halo.haloId) { mutableStateOf(Offset.Zero) }
    val latestWorld by rememberUpdatedState(Offset(worldX, worldY))
    val latestZoom by rememberUpdatedState(zoom)
    val latestOnOpen by rememberUpdatedState(onOpen)
    val latestOnMoveCommit by rememberUpdatedState(onMoveCommit)

    val centerScreenX = worldX * zoom + panX + dragDeltaPx.x
    val centerScreenY = worldY * zoom + panY + dragDeltaPx.y
    if (viewportSize.width > 0 && viewportSize.height > 0) {
        if (centerScreenX < -64f || centerScreenX > viewportSize.width + 64f ||
            centerScreenY < -64f || centerScreenY > viewportSize.height + 64f
        ) {
            return
        }
    }

    Surface(
        modifier = Modifier
            .offset {
                IntOffset(
                    (centerScreenX - pinHalfPx).roundToInt(),
                    (centerScreenY - pinHalfPx).roundToInt(),
                )
            }
            .pointerInput(halo.haloId) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var total = Offset.Zero
                    var dragging = false
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (dragging) {
                                val target = latestWorld + total / latestZoom.coerceAtLeast(0.1f)
                                dragDeltaPx = Offset.Zero
                                latestOnMoveCommit(target.x, target.y)
                            } else {
                                dragDeltaPx = Offset.Zero
                                latestOnOpen()
                            }
                            break
                        }
                        val delta = change.position - change.previousPosition
                        total += delta
                        if (!dragging && total.getDistance() > slop) {
                            dragging = true
                        }
                        if (dragging) {
                            dragDeltaPx = total
                            change.consume()
                        }
                    }
                }
            },
        shape = CircleShape,
        color = pinColor,
        shadowElevation = if (isActive) 6.dp else 3.dp,
        border = if (isActive) BorderStroke(2.dp, Color.White) else null,
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = openDescription,
            tint = Color.White,
            modifier = Modifier
                .padding(6.dp)
                .size(16.dp),
        )
    }
}

@Composable
private fun ImageManipulationOverlay(
    images: List<CanvasImageNodeDto>,
    selectedImageId: String?,
    activeGestureImageId: String?,
    previewImageRect: ImageWorldRect?,
    panX: Float,
    panY: Float,
    zoom: Float,
    viewportSize: IntSize,
    onSelectImage: (String?) -> Unit,
    onPan: (Float, Float) -> Unit,
    onGesturePreview: (String, ImageWorldRect) -> Unit,
    onCommitTransform: (String, ImageWorldRect) -> Unit,
    onDeleteImage: (CanvasImageNodeDto) -> Unit,
    deleteImageDesc: String,
) {
    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)

    val latestImages by rememberUpdatedState(images)
    val latestActiveGestureId by rememberUpdatedState(activeGestureImageId)
    val latestPreviewRect by rememberUpdatedState(previewImageRect)
    val latestPanX by rememberUpdatedState(panX)
    val latestPanY by rememberUpdatedState(panY)
    val latestZoom by rememberUpdatedState(zoom)
    val latestOnSelectImage by rememberUpdatedState(onSelectImage)
    val latestOnPan by rememberUpdatedState(onPan)
    val latestOnPreview by rememberUpdatedState(onGesturePreview)
    val latestOnCommit by rememberUpdatedState(onCommitTransform)

    fun currentRectFor(node: CanvasImageNodeDto): ImageWorldRect =
        if (node.imageId == latestActiveGestureId && latestPreviewRect != null) {
            latestPreviewRect!!
        } else {
            ImageWorldRect(node.x, node.y, node.width, node.height)
        }

    Box(modifier = Modifier.fillMaxSize()) {
        // Unbounded border drawing: immune to parent Box constraints even when enlarged images exceed viewport size
        Canvas(modifier = Modifier.fillMaxSize()) {
            val borderCorner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            images.forEach { node ->
                val rect = if (node.imageId == activeGestureImageId && previewImageRect != null) {
                    previewImageRect
                } else {
                    ImageWorldRect(node.x, node.y, node.width, node.height)
                }
                val leftPx = rect.x * zoom + panX
                val topPx = rect.y * zoom + panY
                val rightPx = (rect.x + rect.w) * zoom + panX
                val bottomPx = (rect.y + rect.h) * zoom + panY
                val isSelected = selectedImageId == node.imageId
                drawRoundRect(
                    color = if (isSelected) primaryColor else outlineColor,
                    topLeft = Offset(leftPx, topPx),
                    size = Size(
                        (rightPx - leftPx).coerceAtLeast(16f),
                        (bottomPx - topPx).coerceAtLeast(16f),
                    ),
                    cornerRadius = borderCorner,
                    style = Stroke(width = if (isSelected) 2.dp.toPx() else 1.dp.toPx()),
                )
            }
        }

        // Unified hit-testing for image selection/dragging and empty-space panning
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val px = latestPanX
                        val py = latestPanY
                        val z = latestZoom.coerceAtLeast(0.1f)
                        val hitNode = latestImages.asReversed().firstOrNull { node ->
                            val r = currentRectFor(node)
                            val l = r.x * z + px
                            val t = r.y * z + py
                            val ri = (r.x + r.w) * z + px
                            val bo = (r.y + r.h) * z + py
                            down.position.x in l..ri && down.position.y in t..bo
                        }
                        if (hitNode != null) {
                            latestOnSelectImage(hitNode.imageId)
                            val startRect = currentRectFor(hitNode)
                            var curRect = startRect
                            var dragging = false
                            var totalDelta = Offset.Zero
                            val slop = viewConfiguration.touchSlop
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (dragging) {
                                        latestOnCommit(hitNode.imageId, curRect)
                                    }
                                    break
                                }
                                val delta = change.position - change.previousPosition
                                totalDelta += delta
                                if (!dragging && totalDelta.getDistance() > slop) {
                                    dragging = true
                                }
                                if (dragging) {
                                    val curZ = latestZoom.coerceAtLeast(0.1f)
                                    curRect = startRect.copy(
                                        x = startRect.x + totalDelta.x / curZ,
                                        y = startRect.y + totalDelta.y / curZ,
                                    )
                                    latestOnPreview(hitNode.imageId, curRect)
                                    change.consume()
                                }
                            }
                        } else {
                            var dragging = false
                            var totalDelta = Offset.Zero
                            val slop = viewConfiguration.touchSlop
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (!dragging) {
                                        latestOnSelectImage(null)
                                    }
                                    break
                                }
                                val delta = change.position - change.previousPosition
                                totalDelta += delta
                                if (!dragging && totalDelta.getDistance() > slop) {
                                    dragging = true
                                }
                                if (dragging) {
                                    latestOnPan(delta.x, delta.y)
                                    change.consume()
                                }
                            }
                        }
                    }
                },
        )

        val selectedNode = images.firstOrNull { it.imageId == selectedImageId }
        if (selectedNode != null) {
            val rect = if (selectedNode.imageId == activeGestureImageId && previewImageRect != null) {
                previewImageRect
            } else {
                ImageWorldRect(selectedNode.x, selectedNode.y, selectedNode.width, selectedNode.height)
            }
            val leftPx = rect.x * zoom + panX
            val topPx = rect.y * zoom + panY
            val rightPx = (rect.x + rect.w) * zoom + panX
            val bottomPx = (rect.y + rect.h) * zoom + panY

            val corners = listOf(
                ResizeCorner.TopLeft to Offset(leftPx, topPx),
                ResizeCorner.TopRight to Offset(rightPx, topPx),
                ResizeCorner.BottomLeft to Offset(leftPx, bottomPx),
                ResizeCorner.BottomRight to Offset(rightPx, bottomPx),
            )
            val handleHitPx = with(density) { 28.dp.toPx() }
            val latestSel by rememberUpdatedState(selectedNode)

            corners.forEach { (corner, centerPx) ->
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (centerPx.x - handleHitPx * 0.5f).roundToInt(),
                                (centerPx.y - handleHitPx * 0.5f).roundToInt(),
                            )
                        }
                        .size(28.dp)
                        .pointerInput(selectedNode.imageId, corner) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val startRect = ImageWorldRect(latestSel.x, latestSel.y, latestSel.width, latestSel.height)
                                val ratio = (startRect.w / startRect.h.coerceAtLeast(1f)).coerceIn(0.05f, 20f)
                                val anchorX = when (corner) {
                                    ResizeCorner.TopLeft, ResizeCorner.BottomLeft -> startRect.x + startRect.w
                                    ResizeCorner.TopRight, ResizeCorner.BottomRight -> startRect.x
                                }
                                val anchorY = when (corner) {
                                    ResizeCorner.TopLeft, ResizeCorner.TopRight -> startRect.y + startRect.h
                                    ResizeCorner.BottomLeft, ResizeCorner.BottomRight -> startRect.y
                                }
                                var curRect = startRect
                                var totalDelta = Offset.Zero
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        latestOnCommit(latestSel.imageId, curRect)
                                        break
                                    }
                                    val delta = change.position - change.previousPosition
                                    totalDelta += delta
                                    change.consume()

                                    val z = latestZoom.coerceAtLeast(0.1f)
                                    val rawW = when (corner) {
                                        ResizeCorner.TopRight, ResizeCorner.BottomRight -> startRect.w + totalDelta.x / z
                                        ResizeCorner.TopLeft, ResizeCorner.BottomLeft -> startRect.w - totalDelta.x / z
                                    }
                                    val rawH = when (corner) {
                                        ResizeCorner.BottomLeft, ResizeCorner.BottomRight -> startRect.h + totalDelta.y / z
                                        ResizeCorner.TopLeft, ResizeCorner.TopRight -> startRect.h - totalDelta.y / z
                                    }
                                    val scaleW = rawW / startRect.w.coerceAtLeast(1f)
                                    val scaleH = rawH / startRect.h.coerceAtLeast(1f)
                                    val chosenScale = if (abs(scaleW - 1f) >= abs(scaleH - 1f)) scaleW else scaleH
                                    val minScale = maxOf(64f / startRect.w, 64f / startRect.h)
                                    val maxScale = minOf(4096f / startRect.w, 4096f / startRect.h)
                                    val clampedScale = chosenScale.coerceIn(minScale, maxOf(minScale, maxScale))
                                    val newW = (startRect.w * clampedScale).coerceAtLeast(64f)
                                    val newH = (newW / ratio).coerceAtLeast(64f)
                                    val newX = when (corner) {
                                        ResizeCorner.TopRight, ResizeCorner.BottomRight -> anchorX
                                        ResizeCorner.TopLeft, ResizeCorner.BottomLeft -> anchorX - newW
                                    }
                                    val newY = when (corner) {
                                        ResizeCorner.BottomLeft, ResizeCorner.BottomRight -> anchorY
                                        ResizeCorner.TopLeft, ResizeCorner.TopRight -> anchorY - newH
                                    }
                                    curRect = ImageWorldRect(newX, newY, newW, newH)
                                    latestOnPreview(latestSel.imageId, curRect)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }

            // Floating delete button positioned safely inside viewport bounds when image is enlarged
            val deleteSizePx = with(density) { 32.dp.toPx() }
            val vpW = viewportSize.width.toFloat().coerceAtLeast(400f)
            val vpH = viewportSize.height.toFloat().coerceAtLeast(400f)
            val placeBelow = topPx < 84f && bottomPx + deleteSizePx + 12f < vpH
            val rawDelX = rightPx - deleteSizePx
            val rawDelY = if (placeBelow) bottomPx + 8f else topPx - deleteSizePx - 8f
            val delX = rawDelX.coerceIn(12f, (vpW - deleteSizePx - 12f).coerceAtLeast(12f)).roundToInt()
            val delY = rawDelY.coerceIn(72f, (vpH - deleteSizePx - 16f).coerceAtLeast(72f)).roundToInt()
            Surface(
                onClick = { onDeleteImage(selectedNode) },
                modifier = Modifier
                    .offset { IntOffset(delX, delY) }
                    .size(32.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                shadowElevation = 3.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = deleteImageDesc,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
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
        color = if (selected) {
            color.copy(alpha = 0.28f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        shadowElevation = 0.dp,
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
