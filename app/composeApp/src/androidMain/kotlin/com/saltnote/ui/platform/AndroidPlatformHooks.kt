package com.saltnote.ui.platform

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.input.motionprediction.MotionEventPredictor
import com.saltnote.bridge.StrokeSample
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

object AndroidPlatformContext {
    lateinit var appContext: Context
    var imagePickerBridge: (() -> ByteArray?)? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun setMulticastLock(enabled: Boolean) {
        if (!::appContext.isInitialized) return
        runCatching {
            if (enabled) {
                if (multicastLock == null) {
                    val wm = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    multicastLock = wm?.createMulticastLock("saltnote-lan-sync")?.apply {
                        setReferenceCounted(false)
                    }
                }
                if (multicastLock?.isHeld == false) {
                    multicastLock?.acquire()
                }
            } else {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                }
            }
        }
    }
}

actual fun defaultVaultDirectory(): String {
    val base = AndroidPlatformContext.appContext.getExternalFilesDir(null)
        ?: AndroidPlatformContext.appContext.filesDir
    return File(base, "SaltNoteVault").apply { mkdirs() }.absolutePath
}

actual fun defaultDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

actual fun loadImageBitmapFromVault(vaultPath: String, relPath: String): ImageBitmap? {
    val file = File(vaultPath, relPath)
    if (!file.exists()) return null
    return runCatching {
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    }.getOrNull()
}

actual fun pickLocalImageBytes(): ByteArray? = AndroidPlatformContext.imagePickerBridge?.invoke()

actual fun pickLocalFolderPath(title: String, initialPath: String): String? = initialPath

actual fun formatUtcTimestamp(utcMs: Long): String {
    if (utcMs <= 0L) return "-"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(utcMs))
}

actual fun setLanMulticastLock(enabled: Boolean) {
    AndroidPlatformContext.setMulticastLock(enabled)
}

/**
 * Low-latency Android native wet-ink SurfaceView with:
 * - Hardware palm rejection (`TOOL_TYPE_PALM`, oversized palm contacts, stylus hover & 1200ms stylus guard,
 *   plus explicit `stylusOnlyMode` toggle where fingers pan/scroll and only stylus draws ink)
 * - Hardware stylus eraser support (`TOOL_TYPE_ERASER` / `BUTTON_STYLUS_PRIMARY`)
 * - Parent scroll lock (`requestDisallowInterceptTouchEvent(true)`) so vertical strokes inside inline
 *   document canvases NEVER conflict with vertical page scrolling
 * - Smooth midpoint quadratic-Bezier wet ink matching dry ink 1:1 WYSIWYG
 */
private class SaltWetInkSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val predictor: MotionEventPredictor = MotionEventPredictor.newInstance(this)
    private val wetPath = Path()
    private val wetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val collected = ArrayList<StrokeSample>(128)
    private var surfaceReady = false
    private var lastEventTimeMs = 0L
    private var lastScreenX = 0f
    private var lastScreenY = 0f

    // Palm rejection & stylus tracking
    private var lastStylusActivityMs = 0L
    private var activeStrokePointerId = MotionEvent.INVALID_POINTER_ID
    private var activeStrokeIsStylus = false
    private var activeHardwareEraser = false

    // Two-finger pan/zoom state
    private var multiTouching = false
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastSpan = 0f

    var toolKind: String = "pen"
    var strokeColorArgb: Int = 0xFF191C1E.toInt()
    var strokeWidthPx: Float = 3.2f
    var panX: Float = 0f
    var panY: Float = 0f
    var zoom: Float = 1f
    var stylusOnlyMode: Boolean = false
    var allowTouchScrollPassThrough: Boolean = false

    var onPanZoom: ((Float, Float, Float, Float, Float) -> Unit)? = null
    var onStrokeCompleted: ((List<StrokeSample>) -> Unit)? = null
    var onEraserMove: ((Float, Float) -> Unit)? = null

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        clearWetBuffer()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            lastStylusActivityMs = SystemClock.uptimeMillis()
            // Cancel any stray finger/palm ink that started just before the pen approached the screen
            if (!activeStrokeIsStylus && collected.isNotEmpty()) {
                abortActiveStroke()
            }
        }
        return super.onHoverEvent(event)
    }

    private fun isStylusTool(event: MotionEvent, pointerIndex: Int = 0): Boolean {
        val tt = event.getToolType(pointerIndex)
        return tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun isLikelyPalm(event: MotionEvent, pointerIndex: Int = 0): Boolean {
        val tt = event.getToolType(pointerIndex)
        if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) return false
        // OEM/framework palm tool type (5) or Android 13+ FLAG_CANCELED palm rejection signal
        if (tt == 5) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (event.flags and MotionEvent.FLAG_CANCELED) != 0
        ) {
            return true
        }
        // Reject broad palm/wrist contacts resting on the glass
        if (event.getTouchMajor(pointerIndex) > 48f || event.getSize(pointerIndex) > 0.14f) {
            return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        // Check if any pointer in this event is a stylus
        var stylusIndex = -1
        for (i in 0 until event.pointerCount) {
            if (isStylusTool(event, i)) {
                stylusIndex = i
                lastStylusActivityMs = SystemClock.uptimeMillis()
                break
            }
        }

        // If stylus touches down while a finger/palm was touching, immediately discard finger/palm stroke
        if (stylusIndex >= 0 && !activeStrokeIsStylus && collected.isNotEmpty()) {
            abortActiveStroke()
        }

        val nowMs = SystemClock.uptimeMillis()
        val recentStylusGuard = (nowMs - lastStylusActivityMs) < 1_200L

        // Reject explicit palm touches unconditionally
        if (stylusIndex < 0 && isLikelyPalm(event, 0)) {
            if (action == MotionEvent.ACTION_DOWN) abortActiveStroke()
            return !allowTouchScrollPassThrough
        }

        // Stylus-only mode OR recent stylus hover/write guard for non-stylus touch
        if (stylusIndex < 0) {
            // Finger touch when stylusOnlyMode is active or palm resting near stylus
            if (stylusOnlyMode || recentStylusGuard) {
                if (allowTouchScrollPassThrough) {
                    // Inside inline document canvas: let finger scroll the document cleanly!
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                if (recentStylusGuard && event.pointerCount == 1) {
                    // Resting palm / single accidental finger tap right after pen lift -> ignore completely
                    return true
                }
                // On full canvas with stylusOnlyMode: finger smoothly pans & pinch-zooms without leaving marks
                parent?.requestDisallowInterceptTouchEvent(true)
                if (event.pointerCount >= 2) {
                    handleTwoFingerPanZoom(event)
                } else {
                    handleSingleFingerPan(event)
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    multiTouching = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }

            // Two-finger pinch/zoom/pan when not in stylus-only mode
            if (event.pointerCount >= 2) {
                if (!allowTouchScrollPassThrough) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    handleTwoFingerPanZoom(event)
                    return true
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return false
            }

            if (multiTouching) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    multiTouching = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
        }

        // Active drawing / erasing / panning -> lock parent ScrollView so vertical strokes never scroll the page!
        val ptrIdx = if (stylusIndex >= 0) stylusIndex else 0
        val ptrId = event.getPointerId(ptrIdx)

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        // Support stylus tail eraser or primary stylus button as instant eraser
        val isHardwareEraser = stylusIndex >= 0 && (
            event.getToolType(ptrIdx) == MotionEvent.TOOL_TYPE_ERASER ||
                (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            )

        predictor.record(event)

        if (toolKind == "pan" && !isHardwareEraser) {
            handleSingleFingerPan(event)
            return true
        }

        if (toolKind == "eraser" || isHardwareEraser || activeHardwareEraser) {
            handleEraser(event, ptrIdx, isHardwareEraser)
            return true
        }

        handleWetInkStroke(event, ptrIdx, ptrId, isStylus = stylusIndex >= 0)
        return true
    }

    private fun handleTwoFingerPanZoom(event: MotionEvent) {
        val midX = (event.getX(0) + event.getX(1)) * 0.5f
        val midY = (event.getY(0) + event.getY(1)) * 0.5f
        val span = hypot(
            (event.getX(0) - event.getX(1)).toDouble(),
            (event.getY(0) - event.getY(1)).toDouble(),
        ).toFloat().coerceAtLeast(1f)

        if (!multiTouching) {
            multiTouching = true
            abortActiveStroke()
            lastMidX = midX
            lastMidY = midY
            lastSpan = span
        } else {
            val dx = midX - lastMidX
            val dy = midY - lastMidY
            val scaleFactor = (span / lastSpan).coerceIn(0.85f, 1.15f)
            onPanZoom?.invoke(dx, dy, scaleFactor, midX, midY)
            lastMidX = midX
            lastMidY = midY
            lastSpan = span
        }
    }

    private fun handleSingleFingerPan(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastMidX = event.x
                lastMidY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastMidX
                val dy = event.y - lastMidY
                lastMidX = event.x
                lastMidY = event.y
                onPanZoom?.invoke(dx, dy, 1f, event.x, event.y)
            }
        }
    }

    private fun handleEraser(event: MotionEvent, ptrIdx: Int, isHardwareEraser: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                activeHardwareEraser = isHardwareEraser
                abortActiveStroke()
                emitErase(event.getX(ptrIdx), event.getY(ptrIdx))
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until event.historySize) {
                    emitErase(event.getHistoricalX(ptrIdx, h), event.getHistoricalY(ptrIdx, h))
                }
                emitErase(event.getX(ptrIdx), event.getY(ptrIdx))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHardwareEraser = false
            }
        }
    }

    private fun emitErase(screenX: Float, screenY: Float) {
        val wx = (screenX - panX) / zoom
        val wy = (screenY - panY) / zoom
        onEraserMove?.invoke(wx, wy)
    }

    private fun handleWetInkStroke(event: MotionEvent, ptrIdx: Int, ptrId: Int, isStylus: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activeStrokePointerId != MotionEvent.INVALID_POINTER_ID && activeStrokeIsStylus && !isStylus) {
                    return
                }
                activeStrokePointerId = ptrId
                activeStrokeIsStylus = isStylus
                collected.clear()
                wetPath.reset()
                val sx = event.getX(ptrIdx)
                val sy = event.getY(ptrIdx)
                wetPath.moveTo(sx, sy)
                lastScreenX = sx
                lastScreenY = sy
                lastEventTimeMs = event.eventTime
                appendWorldSample(sx, sy, event.getPressure(ptrIdx), 0)
                renderWetInk(predicted = null)
            }
            MotionEvent.ACTION_MOVE -> {
                val trackedIdx = event.findPointerIndex(activeStrokePointerId)
                if (trackedIdx < 0) return
                for (h in 0 until event.historySize) {
                    val hx = event.getHistoricalX(trackedIdx, h)
                    val hy = event.getHistoricalY(trackedIdx, h)
                    val hp = event.getHistoricalPressure(trackedIdx, h)
                    val ht = event.getHistoricalEventTime(h)
                    val dt = (ht - lastEventTimeMs).coerceAtLeast(1L).toInt()
                    lastEventTimeMs = ht
                    addSmoothWetSegment(hx, hy)
                    appendWorldSample(hx, hy, hp, dt)
                }
                val sx = event.getX(trackedIdx)
                val sy = event.getY(trackedIdx)
                val dt = (event.eventTime - lastEventTimeMs).coerceAtLeast(1L).toInt()
                lastEventTimeMs = event.eventTime
                addSmoothWetSegment(sx, sy)
                appendWorldSample(sx, sy, event.getPressure(trackedIdx), dt)

                val predicted = predictor.predict()
                renderWetInk(predicted)
                predicted?.recycle()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val upId = event.getPointerId(event.actionIndex)
                if (upId != activeStrokePointerId && event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    return
                }
                val trackedIdx = event.findPointerIndex(activeStrokePointerId).takeIf { it >= 0 } ?: ptrIdx
                val sx = event.getX(trackedIdx)
                val sy = event.getY(trackedIdx)
                val dt = (event.eventTime - lastEventTimeMs).coerceAtLeast(1L).toInt()
                appendWorldSample(sx, sy, event.getPressure(trackedIdx), dt)
                val finished = ArrayList(collected)
                abortActiveStroke()
                if (finished.isNotEmpty()) {
                    onStrokeCompleted?.invoke(finished)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                abortActiveStroke()
            }
        }
    }

    private fun addSmoothWetSegment(x: Float, y: Float) {
        val midX = (lastScreenX + x) * 0.5f
        val midY = (lastScreenY + y) * 0.5f
        wetPath.quadTo(lastScreenX, lastScreenY, midX, midY)
        lastScreenX = x
        lastScreenY = y
    }

    private fun abortActiveStroke() {
        activeStrokePointerId = MotionEvent.INVALID_POINTER_ID
        activeStrokeIsStylus = false
        collected.clear()
        wetPath.reset()
        clearWetBuffer()
    }

    private fun appendWorldSample(screenX: Float, screenY: Float, pressure: Float, dtMs: Int) {
        val wx = (screenX - panX) / zoom
        val wy = (screenY - panY) / zoom
        val last = collected.lastOrNull()
        if (last != null) {
            val d = hypot((wx - last.x).toDouble(), (wy - last.y).toDouble())
            if (d < 0.35) return
        }
        collected.add(StrokeSample(wx, wy, pressure.coerceIn(0.1f, 1.0f), dtMs))
    }

    private fun renderWetInk(predicted: MotionEvent?) {
        if (!surfaceReady) return
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            val isHighlighter = toolKind == "highlighter"
            val isHalo = toolKind.startsWith("halo_")
            wetPaint.color = strokeColorArgb
            wetPaint.alpha = if (isHighlighter) 96 else 255
            wetPaint.strokeWidth = (strokeWidthPx * zoom * if (isHighlighter) 3f else 1f).coerceAtLeast(1.5f)
            wetPaint.pathEffect = if (isHalo) DashPathEffect(floatArrayOf(18f, 12f), 0f) else null

            canvas.drawPath(wetPath, wetPaint)
            if (predicted != null && predicted.pointerCount > 0) {
                canvas.drawLine(lastScreenX, lastScreenY, predicted.x, predicted.y, wetPaint)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun clearWetBuffer() {
        if (!surfaceReady) return
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }
}

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
    val latestOnPanZoom by rememberUpdatedState(onPanZoom)
    val latestOnStroke by rememberUpdatedState(onStrokeCompleted)
    val latestOnErase by rememberUpdatedState(onEraserMove)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx -> SaltWetInkSurfaceView(ctx) },
        update = { view ->
            view.toolKind = toolKind
            view.strokeColorArgb = strokeColor.toArgb()
            view.strokeWidthPx = strokeWidth
            view.panX = panX
            view.panY = panY
            view.zoom = zoom
            view.stylusOnlyMode = stylusOnlyMode
            view.allowTouchScrollPassThrough = allowTouchScrollPassThrough
            view.onPanZoom = latestOnPanZoom
            view.onStrokeCompleted = latestOnStroke
            view.onEraserMove = latestOnErase
        },
    )
}
