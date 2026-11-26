package com.saltnote.ui.platform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.saltnote.ui.components.SaltFilterChip
import com.saltnote.ui.components.StrokeGeometry
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.i18n.SaltStrings
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.Surface as SkiaSurface
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineEvent
import javax.sound.sampled.TargetDataLine
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.roundToInt

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

actual fun readVaultAssetBytes(vaultPath: String, relPath: String): ByteArray? {
    val file = File(vaultPath, relPath)
    if (!file.isFile) return null
    return runCatching { file.readBytes() }.getOrNull()
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

actual fun supportsInboxCameraCapture(): Boolean = false

actual fun hasMicrophonePermission(): Boolean = true

actual fun requestMicrophonePermission(onResult: (Boolean) -> Unit) {
    onResult(true)
}

actual fun currentNetworkState(): PlatformNetworkState =
    PlatformNetworkState(isOnline = true, isMetered = false, isWifiOrEthernet = true)

actual fun observeNetworkState(onChange: (PlatformNetworkState) -> Unit): () -> Unit {
    onChange(currentNetworkState())
    return {}
}

actual fun isNetworkMetered(): Boolean = false

actual fun observeNetworkMetered(onChange: (Boolean) -> Unit): () -> Unit {
    onChange(false)
    return {}
}

actual fun startVoiceRecording(onError: (String) -> Unit): PlatformVoiceRecorderSession? {
    return runCatching {
        val format = AudioFormat(16_000f, 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            onError("No supported microphone input found on desktop")
            return null
        }
        val line = (AudioSystem.getLine(info) as TargetDataLine).apply {
            open(format)
            start()
        }
        val startedAtMs = System.currentTimeMillis()
        val pcmBuffer = ByteArrayOutputStream()
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = thread(isDaemon = true, name = "saltnote-desktop-rec") {
            val buf = ByteArray(4096)
            while (!stopped.get()) {
                val n = runCatching { line.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n > 0) {
                    synchronized(pcmBuffer) { pcmBuffer.write(buf, 0, n) }
                }
            }
        }
        object : PlatformVoiceRecorderSession {
            override fun stopAndRead(): RecordedAudioClip? {
                stopped.set(true)
                runCatching {
                    line.stop()
                    line.close()
                }
                runCatching { worker.join(500) }
                val pcmBytes = synchronized(pcmBuffer) { pcmBuffer.toByteArray() }
                if (pcmBytes.isEmpty()) return null
                val wavOut = ByteArrayOutputStream()
                val ais = javax.sound.sampled.AudioInputStream(
                    ByteArrayInputStream(pcmBytes),
                    format,
                    (pcmBytes.size / format.frameSize).toLong(),
                )
                AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavOut)
                val durationMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(100L).toInt()
                return RecordedAudioClip(wavOut.toByteArray(), "wav", durationMs)
            }

            override fun cancel() {
                stopped.set(true)
                runCatching {
                    line.stop()
                    line.close()
                }
            }
        }
    }.getOrElse { err ->
        onError(err.message ?: "Failed to start microphone")
        null
    }
}

actual fun playVaultAudio(
    vaultPath: String,
    relPath: String,
    onCompleted: () -> Unit,
    onError: (String) -> Unit,
): PlatformAudioPlaybackHandle? {
    val file = File(vaultPath, relPath)
    if (!file.exists()) {
        onError("Audio file not found: $relPath")
        return null
    }
    if (file.extension.equals("wav", ignoreCase = true) ||
        file.extension.equals("aiff", ignoreCase = true)
    ) {
        return runCatching {
            val stream = AudioSystem.getAudioInputStream(file)
            val clip: Clip = AudioSystem.getClip().apply {
                open(stream)
                addLineListener { ev ->
                    if (ev.type == LineEvent.Type.STOP && microsecondPosition >= microsecondLength - 10_000) {
                        close()
                        onCompleted()
                    }
                }
                start()
            }
            object : PlatformAudioPlaybackHandle {
                override fun isPlaying(): Boolean = clip.isRunning
                override fun pause() {
                    if (clip.isRunning) clip.stop()
                }
                override fun resume() {
                    if (!clip.isRunning) clip.start()
                }
                override fun stop() {
                    runCatching {
                        clip.stop()
                        clip.close()
                    }
                }
            }
        }.getOrElse {
            openWithSystemPlayer(file, onCompleted, onError)
            null
        }
    }
    // Option 2.A: Lightweight OS media handoff for .opus/.ogg/.m4a on Desktop (zero C-toolchain bloat)
    openWithSystemPlayer(file, onCompleted, onError)
    return null
}

private fun openWithSystemPlayer(
    file: File,
    onCompleted: () -> Unit,
    onError: (String) -> Unit,
) {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file)
            onCompleted()
        } else {
            onError("Desktop media opener not supported on this OS")
        }
    }.onFailure { err ->
        onError(err.message ?: "Failed to open audio file")
    }
}

private fun encodeSkiaWebpScaled(rawBytes: ByteArray, maxLongEdge: Int, quality: Int): ByteArray? = runCatching {
    val src = SkiaImage.makeFromEncoded(rawBytes)
    val srcW = src.width.coerceAtLeast(1)
    val srcH = src.height.coerceAtLeast(1)
    val longEdge = maxOf(srcW, srcH)
    val scale = if (longEdge > maxLongEdge) maxLongEdge.toFloat() / longEdge.toFloat() else 1f
    val dstW = (srcW * scale).roundToInt().coerceAtLeast(1)
    val dstH = (srcH * scale).roundToInt().coerceAtLeast(1)
    val surface = SkiaSurface.makeRasterN32Premul(dstW, dstH)
    val canvas: SkiaCanvas = surface.canvas
    canvas.drawImageRect(
        src,
        SkiaRect.makeWH(srcW.toFloat(), srcH.toFloat()),
        SkiaRect.makeWH(dstW.toFloat(), dstH.toFloat()),
    )
    val snapshot = surface.makeImageSnapshot()
    snapshot.encodeToData(EncodedImageFormat.WEBP, quality)?.bytes
}.getOrNull()

@Composable
actual fun PlatformInboxCameraCaptureDialog(
    initialPrivate: Boolean,
    onCaptured: (primaryWebp: ByteArray, thumbWebp: ByteArray, isPrivate: Boolean, noteText: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    var isBoardMode by remember { mutableStateOf(true) }
    var isPrivate by remember { mutableStateOf(initialPrivate) }
    var noteText by remember { mutableStateOf("") }
    var pickedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.capturePhoto) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SaltFilterChip(
                        selected = isBoardMode,
                        onClick = { isBoardMode = true },
                        label = { Text(strings.cameraModeBoard) },
                    )
                    SaltFilterChip(
                        selected = !isBoardMode,
                        onClick = { isBoardMode = false },
                        label = { Text(strings.cameraModeJournal) },
                    )
                }
                FilledTonalButton(
                    onClick = {
                        val bytes = pickLocalImageBytes()
                        if (bytes != null) {
                            pickedBytes = bytes
                            errorText = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.PhotoLibrary, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (pickedBytes == null) strings.insertImageDesc else "${strings.insertImageDesc} ✓")
                }
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(strings.captureText) },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isPrivate) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(strings.privateLabel, modifier = Modifier.weight(1f))
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
                if (errorText != null) {
                    Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = pickedBytes != null,
                onClick = {
                    val raw = pickedBytes ?: return@Button
                    val maxEdge = if (isBoardMode) 1600 else 1280
                    val primary = encodeSkiaWebpScaled(raw, maxEdge, 82)
                    val thumb = encodeSkiaWebpScaled(raw, 320, 75)
                    if (primary != null && thumb != null) {
                        onCaptured(primary, thumb, isPrivate, noteText.trim().ifBlank { null })
                    } else {
                        errorText = "Failed to encode WebP image"
                    }
                },
            ) { Text(strings.save) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
    )
}

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
