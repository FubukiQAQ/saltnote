package com.saltnote.ui.components

import androidx.compose.ui.graphics.Color
import com.saltnote.bridge.RectFDto
import com.saltnote.bridge.RenderStrokeDto
import com.saltnote.bridge.StrokeSample

/**
 * Single source of truth for ink width, opacity, pressure smoothing, and optimistic dry-ink
 * construction shared across Android wet ink, Desktop wet ink, and Compose dry ink.
 * Keeping every formula 1:1 identical completely eliminates wet->dry thickness, opacity, and shape jumps.
 */
object StrokeGeometry {
    const val HIGHLIGHTER_ALPHA: Float = 0.35f
    const val HIGHLIGHTER_ALPHA_INT: Int = 89 // round(0.35 * 255)
    const val MIN_PEN_WIDTH: Float = 1.2f
    const val MIN_HIGHLIGHTER_WIDTH: Float = 6.0f
    const val PRESSURE_MIN: Float = 0.15f
    const val PRESSURE_MAX: Float = 1.0f
    const val PRESSURE_EMA_ALPHA: Float = 0.45f

    fun isHighlighter(brush: String): Boolean = brush == "highlighter"
    fun isHalo(brush: String): Boolean = brush.startsWith("halo_")

    /**
     * Unified world-space stroke width formula used by wet AND dry renderers.
     */
    fun worldWidth(brush: String, baseWidth: Float, pressure: Float): Float {
        if (isHighlighter(brush)) {
            return (baseWidth * 3.0f).coerceAtLeast(MIN_HIGHLIGHTER_WIDTH)
        }
        if (isHalo(brush)) {
            return baseWidth.coerceAtLeast(1.8f)
        }
        val p = pressure.coerceIn(PRESSURE_MIN, PRESSURE_MAX)
        return (baseWidth.coerceAtLeast(MIN_PEN_WIDTH) * (0.45f + 0.85f * p)).coerceAtLeast(1.0f)
    }

    /**
     * Unified single-dot radius for tap marks.
     */
    fun dotRadius(brush: String, baseWidth: Float, pressure: Float): Float =
        (worldWidth(brush, baseWidth, pressure) * 0.5f).coerceAtLeast(1.0f)

    /**
     * Unified Compose Color with exact highlighter opacity.
     */
    fun composeColor(brush: String, colorArgb: Long): Color {
        val raw = Color(colorArgb.toInt())
        return if (isHighlighter(brush)) raw.copy(alpha = HIGHLIGHTER_ALPHA) else raw
    }

    /**
     * Applies gentle real-time EMA to stylus/mouse pressure during capture so both wet ink
     * and persisted dry ink share identical smooth pressure contours.
     */
    fun smoothPressure(prevSmoothed: Float?, rawPressure: Float): Float {
        val clamped = rawPressure.coerceIn(PRESSURE_MIN, PRESSURE_MAX)
        if (prevSmoothed == null) return clamped
        return prevSmoothed + PRESSURE_EMA_ALPHA * (clamped - prevSmoothed)
    }

    /**
     * Subtle, natural velocity-simulated pressure for Desktop mouse input when real hardware
     * pressure is absent (returns values in 0.48..0.76 so strokes feel expressive yet consistent).
     */
    fun simulateDesktopMousePressure(distPx: Float, dtMs: Long): Float {
        val speed = distPx / dtMs.coerceAtLeast(1L).toFloat() // px/ms
        // Faster motion => slightly lighter (0.48), slower deliberate motion => slightly firmer (0.74)
        val normalized = (speed / 2.2f).coerceIn(0f, 1f)
        return 0.74f - normalized * 0.26f
    }

    /**
     * Builds an optimistic local `RenderStrokeDto` synchronously on pointer-up so the dry-ink
     * layer paints on the very next frame with zero gap while Rust persists to Loro WAL on IO.
     */
    fun buildOptimisticRenderStroke(
        tempId: String,
        brush: String,
        colorArgb: Long,
        baseWidth: Float,
        points: List<StrokeSample>,
    ): RenderStrokeDto {
        val flat = ArrayList<Float>(points.size * 3)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (p in points) {
            flat.add(p.x)
            flat.add(p.y)
            flat.add(p.pressure.coerceIn(PRESSURE_MIN, PRESSURE_MAX))
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val pad = (worldWidth(brush, baseWidth, 1.0f) * 0.65f).coerceAtLeast(2.0f)
        return RenderStrokeDto(
            strokeId = tempId,
            brush = brush,
            colorArgb = colorArgb,
            baseWidth = baseWidth,
            pointsXyp = flat,
            bbox = RectFDto(
                minX = if (minX.isFinite()) minX - pad else 0f,
                minY = if (minY.isFinite()) minY - pad else 0f,
                maxX = if (maxX.isFinite()) maxX + pad else 0f,
                maxY = if (maxY.isFinite()) maxY + pad else 0f,
            ),
        )
    }
}
