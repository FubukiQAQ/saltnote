package com.saltnote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.saltnote.bridge.DEFAULT_PEN_PALETTE_ARGB
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.state.argbLongToColor
import kotlin.math.abs
import kotlin.math.roundToInt

private val CURATED_PEN_COLORS = listOf(
    Color(0xFF191C1E),
    Color(0xFF1E3A8A),
    Color(0xFF2563EB),
    Color(0xFF0D9488),
    Color(0xFF15803D),
    Color(0xFFD97706),
    Color(0xFFDC2626),
    Color(0xFF7E22CE),
    Color(0xFF52525B),
)

internal fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = 1f,
    )
}

internal fun colorToHsv(color: Color): Triple<Float, Float, Float> {
    val r = color.red.coerceIn(0f, 1f)
    val g = color.green.coerceIn(0f, 1f)
    val b = color.blue.coerceIn(0f, 1f)
    val maxC = maxOf(r, g, b)
    val minC = minOf(r, g, b)
    val delta = maxC - minC
    val h = when {
        delta < 1e-5f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    val normH = ((h % 360f) + 360f) % 360f
    val s = if (maxC < 1e-5f) 0f else (delta / maxC).coerceIn(0f, 1f)
    val v = maxC.coerceIn(0f, 1f)
    return Triple(normH, s, v)
}

internal fun parseHexColorOrNull(input: String): Color? {
    val clean = input.trim().removePrefix("#")
    val parsed = clean.toLongOrNull(16) ?: return null
    val opaqueArgb = when (clean.length) {
        6 -> 0xFF000000L or (parsed and 0x00FFFFFFL)
        8 -> 0xFF000000L or (parsed and 0x00FFFFFFL)
        else -> return null
    }
    return argbLongToColor(opaqueArgb)
}

private const val HEX_DIGITS = "0123456789ABCDEF"

internal fun colorToHexString(color: Color): String {
    val r = (color.red * 255f).roundToInt().coerceIn(0, 255)
    val g = (color.green * 255f).roundToInt().coerceIn(0, 255)
    val b = (color.blue * 255f).roundToInt().coerceIn(0, 255)
    return buildString(7) {
        append('#')
        append(HEX_DIGITS[r ushr 4])
        append(HEX_DIGITS[r and 0x0F])
        append(HEX_DIGITS[g ushr 4])
        append(HEX_DIGITS[g and 0x0F])
        append(HEX_DIGITS[b ushr 4])
        append(HEX_DIGITS[b and 0x0F])
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomPenColorDialog(
    initialColor: Color,
    recentColors: List<Color>,
    onApply: (Color) -> Unit,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    val initialHsv = remember(initialColor) { colorToHsv(initialColor) }
    var hue by remember(initialColor) { mutableStateOf(initialHsv.first) }
    var saturation by remember(initialColor) { mutableStateOf(initialHsv.second) }
    var value by remember(initialColor) { mutableStateOf(initialHsv.third) }
    var currentColor by remember(initialColor) { mutableStateOf(initialColor.copy(alpha = 1f)) }
    var hexInput by remember(initialColor) { mutableStateOf(colorToHexString(initialColor)) }
    var hexError by remember(initialColor) { mutableStateOf(false) }

    fun applyPickedHsv(nextHue: Float, nextSat: Float, nextVal: Float) {
        hue = nextHue
        saturation = nextSat.coerceIn(0f, 1f)
        value = nextVal.coerceIn(0f, 1f)
        currentColor = hsvToColor(hue, saturation, value)
        hexInput = colorToHexString(currentColor)
        hexError = false
    }

    fun selectSwatch(swatch: Color) {
        val normalized = swatch.copy(alpha = 1f)
        val hsv = colorToHsv(normalized)
        hue = hsv.first
        saturation = hsv.second
        value = hsv.third
        currentColor = normalized
        hexInput = colorToHexString(normalized)
        hexError = false
    }

    val defaultSwatches = remember { DEFAULT_PEN_PALETTE_ARGB.map(::argbLongToColor) }
    val hueColors = remember {
        listOf(
            Color.Red,
            Color.Yellow,
            Color.Green,
            Color.Cyan,
            Color.Blue,
            Color.Magenta,
            Color.Red,
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = strings.customColorTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = strings.customColorHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 2D Saturation & Value picker panel
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(hue) {
                                detectTapGestures { pos ->
                                    val s = (pos.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    val v = 1f - (pos.y / size.height.toFloat()).coerceIn(0f, 1f)
                                    applyPickedHsv(hue, s, v)
                                }
                            }
                            .pointerInput(hue) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    val s = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    val v = 1f - (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                                    applyPickedHsv(hue, s, v)
                                }
                            },
                    ) {
                        val huePureColor = hsvToColor(hue, 1f, 1f)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.White, huePureColor),
                            ),
                        )
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black),
                            ),
                        )
                        val cx = saturation.coerceIn(0f, 1f) * size.width
                        val cy = (1f - value.coerceIn(0f, 1f)) * size.height
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.55f),
                            radius = 9.dp.toPx(),
                            center = Offset(cx, cy),
                            style = Stroke(width = 3.dp.toPx()),
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 9.dp.toPx(),
                            center = Offset(cx, cy),
                            style = Stroke(width = 1.8.dp.toPx()),
                        )
                    }
                }

                // Horizontal Hue rainbow slider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(13.dp)),
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(saturation, value) {
                                detectTapGestures { pos ->
                                    val nextHue = ((pos.x / size.width.toFloat()).coerceIn(0f, 1f)) * 360f
                                    applyPickedHsv(nextHue, saturation, value)
                                }
                            }
                            .pointerInput(saturation, value) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    val nextHue = ((change.position.x / size.width.toFloat()).coerceIn(0f, 1f)) * 360f
                                    applyPickedHsv(nextHue, saturation, value)
                                }
                            },
                    ) {
                        drawRect(brush = Brush.horizontalGradient(hueColors))
                        val thumbX = (hue.coerceIn(0f, 360f) / 360f) * size.width
                        val cy = size.height / 2f
                        drawCircle(
                            color = hsvToColor(hue, 1f, 1f),
                            radius = 10.dp.toPx(),
                            center = Offset(thumbX, cy),
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 10.dp.toPx(),
                            center = Offset(thumbX, cy),
                            style = Stroke(width = 2.4.dp.toPx()),
                        )
                    }
                }

                // Preview & Hex input row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .height(46.dp)
                            .width(84.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(initialColor.copy(alpha = 1f)),
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(currentColor),
                        )
                    }
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { next ->
                            val sanitized = next.take(9)
                            hexInput = sanitized
                            val parsed = parseHexColorOrNull(sanitized)
                            if (parsed != null) {
                                val hsv = colorToHsv(parsed)
                                hue = hsv.first
                                saturation = hsv.second
                                value = hsv.third
                                currentColor = parsed
                                hexError = false
                            } else {
                                hexError = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        isError = hexError,
                        label = { Text(strings.hexColorLabel) },
                        supportingText = if (hexError) {
                            { Text(strings.hexInvalidError) }
                        } else {
                            null
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }

                // Default Palette Swatches
                ColorSwatchSection(
                    title = strings.defaultPaletteLabel,
                    colors = defaultSwatches,
                    selectedColor = currentColor,
                    onSelect = ::selectSwatch,
                )

                // Curated Ink Swatches
                ColorSwatchSection(
                    title = strings.curatedPaletteLabel,
                    colors = CURATED_PEN_COLORS,
                    selectedColor = currentColor,
                    onSelect = ::selectSwatch,
                )

                // Recent Custom Colors
                if (recentColors.isNotEmpty()) {
                    ColorSwatchSection(
                        title = strings.recentColorsLabel,
                        colors = recentColors,
                        selectedColor = currentColor,
                        onSelect = ::selectSwatch,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            onResetDefaults()
                            onDismiss()
                        },
                    ) {
                        Text(strings.resetDefaultPalette)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDismiss) {
                            Text(strings.cancel)
                        }
                        Button(
                            onClick = { onApply(currentColor) },
                            enabled = !hexError,
                        ) {
                            Text(strings.applyColor)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSwatchSection(
    title: String,
    colors: List<Color>,
    selectedColor: Color,
    onSelect: (Color) -> Unit,
) {
    val activeHex = remember(selectedColor) { colorToHexString(selectedColor) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            colors.forEach { swatch ->
                val isSelected = colorToHexString(swatch) == activeHex
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        )
                        .clickable { onSelect(swatch) },
                )
            }
        }
    }
}
