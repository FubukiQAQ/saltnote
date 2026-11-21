package com.saltnote.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import com.saltnote.bridge.TessellatedMeshDto
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 512x512 px offscreen GPU-backed tile cache for high-FPS dry-ink rendering (MVP Section 3.2).
 * Pan/zoom gestures directly transform cached textures at 60/120fps; dirty tiles are lazily
 * re-rasterized when strokes mutate or when the discrete LOD zoom bucket changes.
 */
class CanvasTileCache(
    private val maxTiles: Int = 64,
) {
    data class TileKey(val tx: Int, val ty: Int, val lodIndex: Int)

    private val lru = LinkedHashMap<TileKey, ImageBitmap>(maxTiles, 0.75f, true)
    private var lastContentSignature: Int = 0

    fun lodIndexForZoom(zoom: Float): Int = when {
        zoom < 0.65f -> 0 // 0.5x scale (1024 world units per 512px tile)
        zoom < 1.45f -> 1 // 1.0x scale (512 world units per 512px tile)
        zoom < 2.6f -> 2  // 2.0x scale (256 world units per 512px tile)
        else -> 3         // 4.0x scale (128 world units per 512px tile)
    }

    fun lodScaleForIndex(lodIndex: Int): Float = when (lodIndex) {
        0 -> 0.5f
        1 -> 1.0f
        2 -> 2.0f
        else -> 4.0f
    }

    fun tileWorldSpan(lodIndex: Int): Float = TILE_SIZE_PX / lodScaleForIndex(lodIndex)

    fun syncContentVersion(signature: Int) {
        if (signature != lastContentSignature) {
            lru.clear()
            lastContentSignature = signature
        }
    }

    fun clear() {
        lru.clear()
    }

    /**
     * Fetches or rasterizes a 512x512 offscreen tile containing only meshes intersecting the tile's
     * world-space bounds. Returns null if the tile contains zero strokes (skip GPU blit).
     */
    fun getOrRenderTile(
        tx: Int,
        ty: Int,
        lodIndex: Int,
        meshes: List<TessellatedMeshDto>,
    ): ImageBitmap? {
        val key = TileKey(tx, ty, lodIndex)
        lru[key]?.let { return it }

        val scale = lodScaleForIndex(lodIndex)
        val span = TILE_SIZE_PX / scale
        val minX = tx * span
        val minY = ty * span
        val maxX = minX + span
        val maxY = minY + span

        // Quick AABB reject so empty tiles allocate zero bitmaps
        val intersecting = meshes.filter { m ->
            m.bbox.maxX >= minX && m.bbox.minX <= maxX &&
                m.bbox.maxY >= minY && m.bbox.minY <= maxY &&
                m.verticesXy.size >= 6
        }
        if (intersecting.isEmpty()) return null

        val bmp = ImageBitmap(TILE_SIZE_PX, TILE_SIZE_PX)
        val canvas = Canvas(bmp)
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-minX, -minY)

        val paint = Paint().apply { isAntiAlias = true }
        for (mesh in intersecting) {
            val coords = mesh.verticesXy
            val count = coords.size / 2
            val positions = ArrayList<Offset>(count)
            var idx = 0
            while (idx + 1 < coords.size) {
                positions.add(Offset(coords[idx], coords[idx + 1]))
                idx += 2
            }
            val baseColor = Color(mesh.colorArgb.toInt())
            paint.color = if (mesh.brush == "highlighter") {
                baseColor.copy(alpha = 0.35f)
            } else {
                baseColor
            }
            val vertices = Vertices(
                vertexMode = VertexMode.TriangleStrip,
                positions = positions,
                textureCoordinates = positions,
                colors = List(positions.size) { paint.color },
                indices = List(positions.size) { it },
            )
            canvas.drawVertices(vertices, BlendMode.SrcOver, paint)
        }
        canvas.restore()

        if (lru.size >= maxTiles) {
            val eldest = lru.keys.firstOrNull()
            if (eldest != null) lru.remove(eldest)
        }
        lru[key] = bmp
        return bmp
    }

    fun visibleTileRange(
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        panX: Float,
        panY: Float,
        zoom: Float,
        lodIndex: Int,
    ): TileRange {
        val span = tileWorldSpan(lodIndex)
        val worldLeft = -panX / zoom
        val worldTop = -panY / zoom
        val worldRight = (viewportWidthPx - panX) / zoom
        val worldBottom = (viewportHeightPx - panY) / zoom
        return TileRange(
            minTx = floor(worldLeft / span).toInt(),
            maxTx = ceil(worldRight / span).toInt() - 1,
            minTy = floor(worldTop / span).toInt(),
            maxTy = ceil(worldBottom / span).toInt() - 1,
        )
    }

    data class TileRange(val minTx: Int, val maxTx: Int, val minTy: Int, val maxTy: Int)

    companion object {
        const val TILE_SIZE_PX = 512
    }
}
