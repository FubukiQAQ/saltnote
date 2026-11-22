package com.saltnote.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.saltnote.bridge.StrokeSample

expect fun defaultVaultDirectory(): String
expect fun defaultDeviceName(): String
expect fun systemLanguageTag(): String
expect fun saveLastVaultDirectory(path: String)
expect fun loadImageBitmapFromVault(vaultPath: String, relPath: String): ImageBitmap?
expect fun pickLocalImageBytes(): ByteArray?
expect fun pickVaultDirectory(
    initialPath: String,
    strings: com.saltnote.ui.i18n.SaltStrings,
    onPicked: (String) -> Unit,
    onMessage: (String) -> Unit,
)
expect fun exportMarkdownBackupWithPicker(
    client: com.saltnote.bridge.SaltNoteClient,
    initialPath: String,
    strings: com.saltnote.ui.i18n.SaltStrings,
    onResult: (String) -> Unit,
)
expect fun formatUtcTimestamp(utcMs: Long): String
expect fun setLanMulticastLock(enabled: Boolean)

expect fun copyTextToClipboard(text: String)
expect fun readTextFromClipboard(): String?
expect fun supportsLiveCameraQrScan(): Boolean

/**
 * Cross-platform QR pairing scanner dialog:
 * - Android: Live CameraX + ZXing offline QR scanner with runtime CAMERA permission request.
 * - Desktop: Decode QR code from a local image file or paste pairing URI from clipboard.
 */
@Composable
expect fun PlatformQrScannerDialog(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
)

/**
 * Platform-specialized low-latency wet-ink input overlay with hardware palm rejection,
 * unified `StrokeGeometry` WYSIWYG rendering, VSync-aligned zero-blink handoff,
 * and scroll-conflict prevention for both full-page canvas and inline document canvas blocks.
 */
@Composable
expect fun PlatformInkSurface(
    modifier: Modifier,
    toolKind: String,
    strokeColor: Color,
    strokeWidth: Float,
    panX: Float,
    panY: Float,
    zoom: Float,
    stylusOnlyMode: Boolean = false,
    allowTouchScrollPassThrough: Boolean = false,
    onPanZoom: (dx: Float, dy: Float, zoomFactor: Float, pivotX: Float, pivotY: Float) -> Unit,
    onStrokeCompleted: (worldPoints: List<StrokeSample>) -> Unit,
    onEraserMove: (worldX: Float, worldY: Float) -> Unit,
)
