package com.saltnote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ParsedLanPairing(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val deviceName: String = "",
)

/**
 * Encodes & parses `saltnote://pair?host=...&port=...&fp=...&name=...` URIs used for
 * QR code scanning and one-click clipboard pairing across Desktop and Android.
 */
object LanPairingCodec {
    private const val PREFIX = "saltnote://pair?"

    fun encode(host: String, port: Int, fingerprint: String, deviceName: String): String {
        val encHost = URLEncoder.encode(host.trim(), StandardCharsets.UTF_8.name())
        val encFp = URLEncoder.encode(fingerprint.trim(), StandardCharsets.UTF_8.name())
        val encName = URLEncoder.encode(deviceName.trim(), StandardCharsets.UTF_8.name())
        return "${PREFIX}host=$encHost&port=$port&fp=$encFp&name=$encName"
    }

    fun parse(raw: String): ParsedLanPairing? {
        val trimmed = raw.trim()
        val query = when {
            trimmed.startsWith(PREFIX, ignoreCase = true) -> trimmed.substring(PREFIX.length)
            trimmed.contains("host=") && trimmed.contains("fp=") -> trimmed.substringAfter("?")
            else -> return null
        }
        val params = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = pair.substring(0, idx).lowercase()
                val v = runCatching {
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                }.getOrDefault(pair.substring(idx + 1))
                params[k] = v.trim()
            }
        }
        val host = params["host"].orEmpty()
        val port = params["port"]?.toIntOrNull() ?: 47821
        val fp = params["fp"].orEmpty()
        if (host.isBlank() || fp.length < 16 || port !in 1..65535) return null
        return ParsedLanPairing(
            host = host,
            port = port,
            fingerprint = fp,
            deviceName = params["name"].orEmpty(),
        )
    }
}

/**
 * Pure-vector Compose Canvas QR code powered by ZXing `QRCodeWriter` (offline, zero native deps,
 * razor-sharp at any DPI on Desktop and Android tablets).
 */
@Composable
fun SaltQrCodeView(
    payload: String,
    size: Dp = 180.dp,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(payload) {
        runCatching {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
        }.getOrNull()
    }

    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .background(Color.White)
                .padding(10.dp),
        ) {
            if (matrix != null) {
                Canvas(modifier = Modifier.size(size - 20.dp)) {
                    val cols = matrix.width.coerceAtLeast(1)
                    val rows = matrix.height.coerceAtLeast(1)
                    val cellW = this.size.width / cols
                    val cellH = this.size.height / rows
                    for (y in 0 until rows) {
                        for (x in 0 until cols) {
                            if (matrix.get(x, y)) {
                                drawRect(
                                    color = Color(0xFF111416),
                                    topLeft = Offset(x * cellW, y * cellH),
                                    size = Size(cellW + 0.5f, cellH + 0.5f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
