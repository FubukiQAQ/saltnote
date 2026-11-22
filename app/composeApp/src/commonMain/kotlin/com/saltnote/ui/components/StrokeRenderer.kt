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

private data class CachedStrokeGeometry(
    val signature: Int,
    val isSinglePath: Boolean,
    val uniformWidth: Float,
    val singlePath: Path?,
    val segments: List<Pair<Path, Float>>?,
)

private object DryStrokePathCache {
    private const val MAX_ENTRIES = 2048
    private val cache = object : LinkedHashMap<String, CachedStrokeGeometry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedStrokeGeometry>?): Boolean =
            size > MAX_ENTRIES
    }

    fun getOrBuild(stroke: RenderStrokeDto): CachedStrokeGeometry {
        val data = stroke.pointsXyp
        val sig = data.size * 31 + stroke.baseWidth.toBits() + stroke.brush.hashCode()
        val cached = synchronized(cache) { cache[stroke.strokeId] }
        if (cached != null && cached.signature == sig) {
            return cached
        }
        val built = buildGeometry(stroke, sig)
        synchronized(cache) {
            cache[stroke.strokeId] = built
        }
        return built
    }

    private fun buildGeometry(stroke: RenderStrokeDto, sig: Int): CachedStrokeGeometry {
        val data = stroke.pointsXyp
        val count = data.size / 3
        val isHighlighter = StrokeGeometry.isHighlighter(stroke.brush)

        if (count <= 1) {
            return CachedStrokeGeometry(sig, true, 0f, null, null)
        }

        if (isHighlighter || !hasMeaningfulPressureVariation(data, count)) {
            val avgP = averagePressure(data, count)
            val width = StrokeGeometry.worldWidth(stroke.brush, stroke.baseWidth, avgP)
            val path = buildSmoothQuadraticPath(data, count)
            return CachedStrokeGeometry(sig, true, width, path, null)
        }

        val segs = ArrayList<Pair<Path, Float>>(count - 1)
        var prevMidX = data[0]
        var prevMidY = data[1]
        for (i in 1 until count) {
            val prevX = data[(i - 1) * 3]
            val prevY = data[(i - 1) * 3 + 1]
            val prevP = data[(i - 1) * 3 + 2]
            val curX = data[i * 3]
            val curY = data[i * 3 + 1]
            val curP = data[i * 3 + 2]

            val midX = if (i == count - 1) curX else (prevX + curX) * 0.5f
            val midY = if (i == count - 1) curY else (prevY + curY) * 0.5f
            val avgP = (prevP + curP) * 0.5f
            val w = StrokeGeometry.worldWidth(stroke.brush, stroke.baseWidth, avgP)

            val segPath = Path().apply {
                moveTo(prevMidX, prevMidY)
                quadraticTo(prevX, prevY, midX, midY)
            }
            segs.add(segPath to w)
            prevMidX = midX
            prevMidY = midY
        }
        return CachedStrokeGeometry(sig, false, 0f, null, segs)
    }
}

/**
 * WYSIWYG native-DPI anti-aliased quadratic-Bezier dry-ink renderer.
 * Uses `StrokeGeometry` 1:1 with Android & Desktop wet-ink surfaces so wet->dry handoff
 * has zero thickness, opacity, or contour jump, while caching compiled `Path`s per strokeId.
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

    val color: Color = StrokeGeometry.composeColor(stroke.brush, stroke.colorArgb)

    if (count == 1) {
        val x = data[0]
        val y = data[1]
        val p = data[2]
        val r = StrokeGeometry.dotRadius(stroke.brush, stroke.baseWidth, p)
        drawCircle(color = color, radius = r, center = Offset(x, y))
        return
    }

    val geom = DryStrokePathCache.getOrBuild(stroke)
    if (geom.isSinglePath && geom.singlePath != null) {
        drawPath(
            path = geom.singlePath,
            color = color,
            style = Stroke(
                width = geom.uniformWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
        return
    }

    val segs = geom.segments ?: return
    for ((segPath, w) in segs) {
        drawPath(
            path = segPath,
            color = color,
            style = Stroke(
                width = w,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
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
    return abs(maxP - minP) > 0.05f
}

private fun averagePressure(data: List<Float>, count: Int): Float {
    var sum = 0f
    for (i in 0 until count) {
        sum += data[i * 3 + 2]
    }
    return (sum / count.coerceAtLeast(1)).coerceIn(StrokeGeometry.PRESSURE_MIN, StrokeGeometry.PRESSURE_MAX)
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
