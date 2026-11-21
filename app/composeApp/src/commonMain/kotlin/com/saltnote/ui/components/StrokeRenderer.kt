package com.saltnote.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.saltnote.bridge.RenderStrokeDto
import kotlin.math.abs

/**
 * WYSIWYG native-DPI anti-aliased quadratic-Bezier stroke renderer.
 * Produces identical silky-smooth strokes for both wet ink and persisted dry ink,
 * completely eliminating non-AA triangle-strip sawtooth jaggies.
 */
fun DrawScope.drawWysiwygStrokes(
    strokes: List<RenderStrokeDto>,
    viewportMinX: Float,
    viewportMinY: Float,
    viewportMaxX: Float,
    viewportMaxY: Float,
) {
    for (stroke in strokes) {
        if (
            stroke.bbox.maxX < viewportMinX ||
            stroke.bbox.minX > viewportMaxX ||
            stroke.bbox.maxY < viewportMinY ||
            stroke.bbox.minY > viewportMaxY
        ) {
            continue
        }
        drawSingleWysiwygStroke(stroke)
    }
}

private fun DrawScope.drawSingleWysiwygStroke(stroke: RenderStrokeDto) {
    val data = stroke.pointsXyp
    val count = data.size / 3
    if (count == 0) return

    val rawColor = Color(stroke.colorArgb.toInt())
    val isHighlighter = stroke.brush == "highlighter"
    val color = if (isHighlighter) rawColor.copy(alpha = 0.35f) else rawColor
    val baseW = if (isHighlighter) (stroke.baseWidth * 3.0f).coerceAtLeast(6f) else stroke.baseWidth.coerceAtLeast(1.2f)

    if (count == 1) {
        val x = data[0]
        val y = data[1]
        val p = data[2].coerceIn(0.15f, 1f)
        val r = (baseW * if (isHighlighter) 0.5f else (0.35f + 0.45f * p)).coerceAtLeast(1f)
        drawCircle(color = color, radius = r, center = Offset(x, y))
        return
    }

    // Highlighter or nearly-constant pressure strokes: draw one single silky quadratic Bezier Path
    if (isHighlighter || !hasMeaningfulPressureVariation(data, count)) {
        val avgP = averagePressure(data, count)
        val width = if (isHighlighter) baseW else (baseW * (0.55f + 0.65f * avgP)).coerceAtLeast(1.2f)
        val path = buildSmoothQuadraticPath(data, count)
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = width,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
        return
    }

    // Pressure-sensitive pen: smooth quadratic Bezier midpoint segments with gentle pressure modulation
    var prevMidX = data[0]
    var prevMidY = data[1]
    val segPath = Path()
    for (i in 1 until count) {
        val prevX = data[(i - 1) * 3]
        val prevY = data[(i - 1) * 3 + 1]
        val prevP = data[(i - 1) * 3 + 2]
        val curX = data[i * 3]
        val curY = data[i * 3 + 1]
        val curP = data[i * 3 + 2]

        val midX = if (i == count - 1) curX else (prevX + curX) * 0.5f
        val midY = if (i == count - 1) curY else (prevY + curY) * 0.5f
        val p = ((prevP + curP) * 0.5f).coerceIn(0.15f, 1f)
        val w = (baseW * (0.45f + 0.85f * p)).coerceAtLeast(1.0f)

        segPath.reset()
        segPath.moveTo(prevMidX, prevMidY)
        segPath.quadraticTo(prevX, prevY, midX, midY)
        drawPath(
            path = segPath,
            color = color,
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

private fun hasMeaningfulPressureVariation(data: List<Float>, count: Int): Boolean {
    var minP = Float.MAX_VALUE
    var maxP = Float.MIN_VALUE
    for (i in 0 until count) {
        val p = data[i * 3 + 2]
        if (p < minP) minP = p
        if (p > maxP) maxP = p
    }
    return abs(maxP - minP) > 0.08f
}

private fun averagePressure(data: List<Float>, count: Int): Float {
    var sum = 0f
    for (i in 0 until count) {
        sum += data[i * 3 + 2]
    }
    return (sum / count.coerceAtLeast(1)).coerceIn(0.2f, 1f)
}

private fun buildSmoothQuadraticPath(data: List<Float>, count: Int): Path {
    val path = Path()
    val x0 = data[0]
    val y0 = data[1]
    path.moveTo(x0, y0)
    if (count == 2) {
        path.lineTo(data[3], data[4])
        return path
    }
    for (i in 1 until count - 1) {
        val cx = data[i * 3]
        val cy = data[i * 3 + 1]
        val nx = data[(i + 1) * 3]
        val ny = data[(i + 1) * 3 + 1]
        path.quadraticTo(cx, cy, (cx + nx) * 0.5f, (cy + ny) * 0.5f)
    }
    val last = (count - 1) * 3
    path.lineTo(data[last], data[last + 1])
    return path
}
