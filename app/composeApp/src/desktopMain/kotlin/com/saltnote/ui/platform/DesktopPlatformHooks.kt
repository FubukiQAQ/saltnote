package com.saltnote.ui.platform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.saltnote.bridge.SaltNoteClient
import com.saltnote.bridge.StrokeSample
import com.saltnote.ui.components.StrokeGeometry
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.i18n.SaltStrings
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.hypot

private fun saltNoteDesktopConfigDir(): File {
    val os = (System.getProperty("os.name") ?: "").lowercase()
    val base = when {
        os.contains("win") -> System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home") ?: ".", "AppData/Local")
        os.contains("mac") -> File(System.getProperty("user.home") ?: ".", "Library/Application Support")
        else -> System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home") ?: ".", ".config")
    }
    return File(base, "SaltNote").apply { mkdirs() }
}

private fun vaultConfigFile(): File = File(saltNoteDesktopConfigDir(), "vault_path.txt")

actual fun systemLanguageTag(): String = Locale.getDefault().toLanguageTag()

actual fun saveLastVaultDirectory(path: String) {
    runCatching {
        vaultConfigFile().writeText(path.trim())
    }
}

actual fun defaultVaultDirectory(): String {
    val saved = runCatching {
        vaultConfigFile().takeIf { it.isFile }?.readText()?.trim()
    }.getOrNull()
    if (!saved.isNullOrBlank()) {
        val dir = File(saved)
        if (dir.exists() || dir.mkdirs()) {
            return dir.absolutePath
        }
    }
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
        dialogTitle = "SaltNote"
        fileFilter = FileNameExtensionFilter("Images (PNG, JPG, WebP)", "png", "jpg", "jpeg", "webp")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        runCatching { chooser.selectedFile.readBytes() }.getOrNull()
    } else {
        null
    }
}

actual fun pickVaultDirectory(
    initialPath: String,
    strings: SaltStrings,
    onPicked: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val chooser = JFileChooser(File(initialPath).takeIf { it.exists() }).apply {
        dialogTitle = strings.vaultDirectoryTitle
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        val selected = chooser.selectedFile.absolutePath
        saveLastVaultDirectory(selected)
        onPicked(selected)
    }
}

actual fun exportMarkdownBackupWithPicker(
    client: SaltNoteClient,
    initialPath: String,
    strings: SaltStrings,
    onResult: (String) -> Unit,
) {
    val chooser = JFileChooser(File(initialPath).takeIf { it.exists() }).apply {
        dialogTitle = strings.exportMarkdownBackup
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        val outDir = chooser.selectedFile.absolutePath
        runCatching {
            client.exportMarkdownBackup(outDir)
        }.onSuccess { count ->
            onResult("${strings.backupExportedToast} ($count)")
        }.onFailure { err ->
            onResult("${strings.backupFailedPrefix}${err.message ?: ""}")
        }
    }
}

private val utcFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

actual fun formatUtcTimestamp(utcMs: Long): String {
    if (utcMs <= 0L) return "-"
    return runCatching { utcFmt.format(Instant.ofEpochMilli(utcMs)) }.getOrDefault("-")
}

actual fun setLanMulticastLock(enabled: Boolean) = Unit

actual fun copyTextToClipboard(text: String) {
    runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }
}

actual fun readTextFromClipboard(): String? = runCatching {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
        clipboard.getData(DataFlavor.stringFlavor) as? String
    } else {
        null
    }
}.getOrNull()

actual fun supportsLiveCameraQrScan(): Boolean = false

private fun decodeQrFromImageFile(file: File): String? = runCatching {
    val img = ImageIO.read(file) ?: return null
    val w = img.width
    val h = img.height
    val pixels = IntArray(w * h)
    img.getRGB(0, 0, w, h, pixels, 0, w)
    val source = RGBLuminanceSource(w, h, pixels)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    MultiFormatReader().decode(bitmap).text
}.getOrNull()

@Composable
actual fun PlatformQrScannerDialog(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    var pastedUri by remember { mutableStateOf(readTextFromClipboard().orEmpty().takeIf { it.contains("saltnote://pair") }.orEmpty()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.pairingDialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = strings.pairingDialogPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pastedUri,
                    onValueChange = {
                        pastedUri = it
                        errorText = null
                    },
                    label = { Text(strings.pairingUriLabel) },
                    placeholder = { Text("saltnote://pair?host=192.168.1.10&port=47821&fp=...") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    onClick = {
                        val chooser = JFileChooser().apply {
                            dialogTitle = strings.decodeQrFromImage
                            fileFilter = FileNameExtensionFilter("Images (PNG, JPG)", "png", "jpg", "jpeg")
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val decoded = decodeQrFromImageFile(chooser.selectedFile)
                            if (!decoded.isNullOrBlank()) {
                                onScanned(decoded)
                            } else {
                                errorText = strings.invalidQrImageError
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.decodeQrFromImage)
                }
                if (errorText != null) {
                    Text(
                        text = errorText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = pastedUri.trim()
                    if (trimmed.isNotEmpty()) {
                        onScanned(trimmed)
                    } else {
                        errorText = strings.missingPairingUriError
                    }
                },
            ) {
                Text(strings.connectAndSync)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
    )
}

private data class DesktopWetPoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
)

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

    val activeWetPoints = remember { mutableStateListOf<DesktopWetPoint>() }

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

                    // In stylus-only mode on touch-screen laptops, let finger scroll/pan instead of drawing
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

                    if (tool == "select") {
                        return@awaitEachGesture
                    }

                    // Consume down immediately so vertical strokes inside inline canvases never fight parent scroll
                    down.consume()
                    val collected = mutableListOf<StrokeSample>()
                    activeWetPoints.clear()
                    var prevScreen = down.position
                    var prevTime = down.uptimeMillis
                    var smoothedP: Float? = null

                    fun appendSample(screenPos: Offset, uptimeMs: Long, rawPressure: Float, forceAccept: Boolean = false) {
                        val dt = (uptimeMs - prevTime).coerceAtLeast(1L)
                        val dist = hypot(
                            (screenPos.x - prevScreen.x).toDouble(),
                            (screenPos.y - prevScreen.y).toDouble(),
                        ).toFloat()
                        if (!forceAccept && collected.isNotEmpty() && dist < 0.75f) return

                        val targetP = if (rawPressure > 0.01f && rawPressure != 1f) {
                            rawPressure.coerceIn(StrokeGeometry.PRESSURE_MIN, StrokeGeometry.PRESSURE_MAX)
                        } else if (collected.isEmpty()) {
                            0.62f
                        } else {
                            StrokeGeometry.simulateDesktopMousePressure(dist, dt)
                        }
                        val finalP = StrokeGeometry.smoothPressure(smoothedP, targetP)
                        smoothedP = finalP

                        val wx = (screenPos.x - currentPanX) / currentZoom
                        val wy = (screenPos.y - currentPanY) / currentZoom
                        collected.add(StrokeSample(wx, wy, finalP, dt.toInt()))
                        activeWetPoints.add(DesktopWetPoint(screenPos.x, screenPos.y, finalP))
                        prevScreen = screenPos
                        prevTime = uptimeMs
                    }

                    appendSample(down.position, down.uptimeMillis, down.pressure, forceAccept = true)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            appendSample(change.position, change.uptimeMillis, change.pressure, forceAccept = false)
                            break
                        }
                        appendSample(change.position, change.uptimeMillis, change.pressure, forceAccept = false)
                        change.consume()
                    }

                    // Synchronously publish optimistic dry-ink BEFORE clearing wet ink -> 0-frame blink!
                    if (collected.isNotEmpty()) {
                        currentOnStrokeCompleted(collected)
                    }
                    activeWetPoints.clear()
                }
            },
    ) {
        val count = activeWetPoints.size
        if (count == 0) return@Canvas

        val isHalo = StrokeGeometry.isHalo(toolKind)
        val isHighlighter = StrokeGeometry.isHighlighter(toolKind)
        val effectiveColor = if (isHighlighter) strokeColor.copy(alpha = StrokeGeometry.HIGHLIGHTER_ALPHA) else strokeColor
        val dashEffect = if (isHalo) PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f) else null

        if (count == 1) {
            val pt = activeWetPoints[0]
            val r = StrokeGeometry.dotRadius(toolKind, strokeWidth, pt.pressure) * zoom
            drawCircle(color = effectiveColor, radius = r, center = Offset(pt.x, pt.y))
            return@Canvas
        }

        if (isHighlighter || isHalo) {
            val path = Path().apply {
                moveTo(activeWetPoints[0].x, activeWetPoints[0].y)
                if (count == 2) {
                    lineTo(activeWetPoints[1].x, activeWetPoints[1].y)
                } else {
                    for (i in 1 until count - 1) {
                        val cur = activeWetPoints[i]
                        val next = activeWetPoints[i + 1]
                        quadraticTo(cur.x, cur.y, (cur.x + next.x) * 0.5f, (cur.y + next.y) * 0.5f)
                    }
                    val last = activeWetPoints.last()
                    lineTo(last.x, last.y)
                }
            }
            val w = StrokeGeometry.worldWidth(toolKind, strokeWidth, 0.65f) * zoom
            drawPath(
                path = path,
                color = effectiveColor,
                style = Stroke(
                    width = w,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = dashEffect,
                ),
            )
            return@Canvas
        }

        // Exact 1:1 midpoint quadratic-Bezier per-segment pressure rendering matching StrokeRenderer.kt
        var prevMidX = activeWetPoints[0].x
        var prevMidY = activeWetPoints[0].y
        val segPath = Path()
        for (i in 1 until count) {
            val prev = activeWetPoints[i - 1]
            val cur = activeWetPoints[i]
            val midX = if (i == count - 1) cur.x else (prev.x + cur.x) * 0.5f
            val midY = if (i == count - 1) cur.y else (prev.y + cur.y) * 0.5f
            val avgP = (prev.pressure + cur.pressure) * 0.5f
            val w = StrokeGeometry.worldWidth(toolKind, strokeWidth, avgP) * zoom

            segPath.reset()
            segPath.moveTo(prevMidX, prevMidY)
            segPath.quadraticTo(prev.x, prev.y, midX, midY)
            drawPath(
                path = segPath,
                color = effectiveColor,
                style = Stroke(
                    width = w,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            prevMidX = midX
            prevMidY = midY
        }
    }
}
