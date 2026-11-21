package com.saltnote.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.saltnote.bridge.StrokeSample

expect fun defaultVaultDirectory(): String
expect fun defaultDeviceName(): String
expect fun loadImageBitmapFromVault(vaultPath: String, relPath: String): ImageBitmap?
expect fun pickLocalImageBytes(): ByteArray?
expect fun pickLocalFolderPath(title: String, initialPath: String): String?
expect fun formatUtcTimestamp(utcMs: Long): String
expect fun setLanMulticastLock(enabled: Boolean)

/**
 * Platform-specialized low-latency wet-ink input overlay with hardware palm rejection
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
