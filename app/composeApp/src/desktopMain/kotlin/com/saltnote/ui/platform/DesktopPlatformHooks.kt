package com.saltnote.ui.platform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import com.saltnote.bridge.StrokeSample
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.hypot

actual fun defaultVaultDirectory(): String {
    val home = System.getProperty("user.home")
    return File(home, "SaltNoteVault").apply { mkdirs() }.absolutePath
}

actual fun defaultDeviceName(): String {
    val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull().orEmpty()
    val os = System.getProperty("os.name").orEmpty().split(" ").firstOrNull() ?: "Desktop"
    return if (host.isNotBlank()) "$host ($os)" else "SaltNote-$os"
}

actual fun loadImageBitmapFromVault(vaultPath: String, relPath: String): ImageBitmap? {
    val file = File(vaultPath, relPath)
    if (!file.exists()) return null
    return runCatching {
        val bytes = file.readBytes()
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

actual fun pickLocalImageBytes(): ByteArray? {
    val chooser = JFileChooser().apply {
        dialogTitle = "选择图片"
        fileFilter = FileNameExtensionFilter("Images (PNG, JPG, WebP)", "png", "jpg", "jpeg", "webp")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        runCatching { chooser.selectedFile.readBytes() }.getOrNull()
    } else {
        null
    }
}

actual fun pickLocalFolderPath(title: String, initialPath: String): String? {
    val chooser = JFileChooser(File(initialPath).takeIf { it.exists() }).apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else {
        null
    }
}

private val utcFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

actual fun formatUtcTimestamp(utcMs: Long): String {
    if (utcMs <= 0L) return "-"
    return runCatching { utcFmt.format(Instant.ofEpochMilli(utcMs)) }.getOrDefault("-")
}

actual fun setLanMulticastLock(enabled: Boolean) = Unit

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun PlatformInkSurface(
    modifier: Modifier,
    toolKind: String,
    strokeColor: Color,
    strokeWidth: Float,
    panX: Float,
    panY: Float,
    zoom: Float,
    stylusOnlyMode: Boolean,
    allowTouchScrollPassThrough: Boolean,
    onPanZoom: (dx: Float, dy: Float, zoomFactor: Float, pivotX: Float, pivotY: Float) -> Unit,
    onStrokeCompleted: (worldPoints: List<StrokeSample>) -> Unit,
    onEraserMove: (worldX: Float, worldY: Float) -> Unit,
) {
    val currentTool by rememberUpdatedState(toolKind)
    val currentPanX by rememberUpdatedState(panX)
    val currentPanY by rememberUpdatedState(panY)
    val currentZoom by rememberUpdatedState(zoom)
    val currentStylusOnly by rememberUpdatedState(stylusOnlyMode)
    val currentPassThrough by rememberUpdatedState(allowTouchScrollPassThrough)
    val currentOnPanZoom by rememberUpdatedState(onPanZoom)
    val currentOnStrokeCompleted by rememberUpdatedState(onStrokeCompleted)
    val currentOnEraserMove by rememberUpdatedState(onEraserMove)

    val activeScreenPoints = remember { mutableStateListOf<Offset>() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onPointerEvent(PointerEventType.Scroll) { event ->
                if (currentPassThrough) return@onPointerEvent
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val deltaY = change.scrollDelta.y
                if (deltaY != 0f) {
                    val factor = if (deltaY < 0f) 1.12f else 1f / 1.12f
                    currentOnPanZoom(0f, 0f, factor, change.position.x, change.position.y)
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val isTouch = down.type == PointerType.Touch
                    val isRightDrag = currentEvent.buttons.isSecondaryPressed
                    val tool = currentTool

                    // In stylus-only mode on touch-screen Windows laptops, let finger scroll/pan instead of drawing
                    if (currentStylusOnly && isTouch) {
                        if (currentPassThrough) return@awaitEachGesture
                        var lastPos = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val dx = change.position.x - lastPos.x
                            val dy = change.position.y - lastPos.y
                            lastPos = change.position
                            currentOnPanZoom(dx, dy, 1f, change.position.x, change.position.y)
                            change.consume()
                        }
                        return@awaitEachGesture
                    }

                    if (tool == "pan" || isRightDrag) {
                        down.consume()
                        var lastPos = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val dx = change.position.x - lastPos.x
                            val dy = change.position.y - lastPos.y
                            lastPos = change.position
                            currentOnPanZoom(dx, dy, 1f, change.position.x, change.position.y)
                            change.consume()
                        }
                        return@awaitEachGesture
                    }

                    if (tool == "eraser") {
                        down.consume()
                        fun emitErase(pos: Offset) {
                            val wx = (pos.x - currentPanX) / currentZoom
                            val wy = (pos.y - currentPanY) / currentZoom
                            currentOnEraserMove(wx, wy)
                        }
                        emitErase(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            emitErase(change.position)
                            change.consume()
                        }
                        return@awaitEachGesture
                    }

                    // Consume down immediately so vertical strokes inside inline canvases never fight parent scroll
                    down.consume()
                    val collected = mutableListOf<StrokeSample>()
                    activeScreenPoints.clear()
                    var prevScreen = down.position
                    var prevTime = down.uptimeMillis

                    fun appendSample(screenPos: Offset, uptimeMs: Long, rawPressure: Float) {
                        val dt = (uptimeMs - prevTime).coerceAtLeast(1L)
                        val dist = hypot(
                            (screenPos.x - prevScreen.x).toDouble(),
                            (screenPos.y - prevScreen.y).toDouble(),
                        ).toFloat()
                        if (collected.isNotEmpty() && dist < 0.75f) return
                        val simulatedPressure = if (rawPressure > 0.01f && rawPressure != 1f) {
                            rawPressure.coerceIn(0.15f, 1f)
                        } else {
                            0.65f
                        }
                        val wx = (screenPos.x - currentPanX) / currentZoom
                        val wy = (screenPos.y - currentPanY) / currentZoom
                        collected.add(StrokeSample(wx, wy, simulatedPressure, dt.toInt()))
                        activeScreenPoints.add(screenPos)
                        prevScreen = screenPos
                        prevTime = uptimeMs
                    }

                    appendSample(down.position, down.uptimeMillis, down.pressure)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            break
                        }
                        appendSample(change.position, change.uptimeMillis, change.pressure)
                        change.consume()
                    }

                    activeScreenPoints.clear()
                    if (collected.isNotEmpty()) {
                        currentOnStrokeCompleted(collected)
                    }
                }
            },
    ) {
        if (activeScreenPoints.size >= 2) {
            val path = Path().apply {
                moveTo(activeScreenPoints[0].x, activeScreenPoints[0].y)
                if (activeScreenPoints.size == 2) {
                    lineTo(activeScreenPoints[1].x, activeScreenPoints[1].y)
                } else {
                    for (i in 1 until activeScreenPoints.size - 1) {
                        val cur = activeScreenPoints[i]
                        val next = activeScreenPoints[i + 1]
                        quadraticTo(cur.x, cur.y, (cur.x + next.x) * 0.5f, (cur.y + next.y) * 0.5f)
                    }
                    val last = activeScreenPoints.last()
                    lineTo(last.x, last.y)
                }
            }
            val isHalo = toolKind.startsWith("halo_")
            val isHighlighter = toolKind == "highlighter"
            drawPath(
                path = path,
                color = if (isHighlighter) strokeColor.copy(alpha = 0.35f) else strokeColor,
                style = Stroke(
                    width = (strokeWidth * zoom * if (isHighlighter) 3f else 1f).coerceAtLeast(1.5f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = if (isHalo) PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f) else null,
                ),
            )
        }
    }
}
