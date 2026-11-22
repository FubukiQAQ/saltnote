package com.saltnote.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.saltnote.bridge.StrokeSample
import com.saltnote.ui.components.CalloutKind
import com.saltnote.ui.components.LanPairingCodec
import com.saltnote.ui.components.MdBlock
import com.saltnote.ui.components.StrokeGeometry
import com.saltnote.ui.components.parseMarkdownBlocks
import com.saltnote.ui.components.stripMathDelimiters
import com.saltnote.ui.i18n.AppLanguage
import com.saltnote.ui.i18n.resolveAiOutputLanguageCode
import com.saltnote.ui.i18n.resolveAppLanguage
import com.saltnote.ui.i18n.stringsFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SaltUiComponentsTest {

    @Test
    fun parseMarkdownHandlesNewAndLegacySaltHaloCalloutsRecursively() {
        val md = """
            > [!salt-amber] 琥珀黄 · 概念扩充
            >
            > ### 核心公式
            > - 欧拉公式：${'$'}e^{i\pi} + 1 = 0${'$'}
            >
            > ${'$'}${'$'}
            > \int_0^\infty e^{-x^2} dx = \frac{\sqrt{\pi}}{2}
            > ${'$'}${'$'}

            > **Salt Halo (builtin.glacier_card)**
            >
            > ```json
            > [{"front":"${'$'}F = ma${'$'} 是什么定律？","back":"牛顿第二定律"}]
            > ```

            > **Salt Halo (builtin.mineral_ocr)**
            >
            > ```latex
            > \nabla \cdot \mathbf{E} = \frac{\rho}{\varepsilon_0}
            > ```
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md.lines())
        val callouts = blocks.filterIsInstance<MdBlock.Callout>()
        assertEquals(3, callouts.size)

        // 1. New Amber Callout recursively parsed into Heading + Bullet + MathBlock
        val amber = callouts[0]
        assertEquals(CalloutKind.SaltAmber, amber.kind)
        assertTrue(amber.children.any { it is MdBlock.Heading && it.level == 3 })
        assertTrue(amber.children.any { it is MdBlock.BulletItem })
        assertTrue(amber.children.any { it is MdBlock.MathBlock && it.latex.contains("\\frac{\\sqrt{\\pi}}{2}") })

        // 2. Legacy Glacier Callout auto-recovers JSON flashcards instead of displaying raw JSON
        val glacier = callouts[1]
        assertEquals(CalloutKind.SaltGlacier, glacier.kind)
        assertEquals("冰川蓝 · 提炼闪卡", glacier.title)
        assertEquals(1, glacier.legacyCards.size)
        assertEquals("牛顿第二定律", glacier.legacyCards[0].back)

        // 3. Legacy Mineral OCR Callout parses fenced ```latex block as MathBlock
        val mineral = callouts[2]
        assertEquals(CalloutKind.SaltMineral, mineral.kind)
        assertEquals("矿石灰 · 笔迹清洗 OCR", mineral.title)
        assertTrue(mineral.children.any { it is MdBlock.MathBlock && it.latex.contains("\\varepsilon_0") })
    }

    @Test
    fun parseMarkdownSupportsTablesAndBracketDisplayMath() {
        val md = """
            | 符号 | 含义 |
            | --- | --- |
            | ${'$'}\alpha${'$'} | 学习率 |

            \[
            x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
            \]
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md.lines())
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("符号", "含义"), table.headers)
        assertEquals(1, table.rows.size)

        val math = blocks.filterIsInstance<MdBlock.MathBlock>().single()
        assertTrue(math.latex.contains("\\sqrt{b^2 - 4ac}"))
        assertEquals("E = mc^2", stripMathDelimiters("$$ E = mc^2 $$"))
    }

    @Test
    fun strokeGeometryMaintainsUnifiedWidthAndOptimisticDto() {
        val wPenLight = StrokeGeometry.worldWidth("pen", 4f, 0.2f)
        val wPenHeavy = StrokeGeometry.worldWidth("pen", 4f, 0.9f)
        assertTrue(wPenHeavy > wPenLight)
        assertEquals(12f, StrokeGeometry.worldWidth("highlighter", 4f, 0.2f))
        assertEquals(12f, StrokeGeometry.worldWidth("highlighter", 4f, 1.0f))
        assertEquals(6f, StrokeGeometry.worldWidth("highlighter", 1.5f, 0.5f))

        val pts = listOf(
            StrokeSample(10f, 20f, 0.5f, 0),
            StrokeSample(30f, 40f, 0.8f, 4),
        )
        val dto = StrokeGeometry.buildOptimisticRenderStroke("pending-1", "pen", 0xFF191C1EL, 3f, pts)
        assertEquals("pending-1", dto.strokeId)
        assertEquals(6, dto.pointsXyp.size)
        assertTrue(dto.bbox.minX < 10f && dto.bbox.maxX > 30f)
    }

    @Test
    fun lanPairingCodecRoundtripsAndRejectsInvalidInput() {
        val uri = LanPairingCodec.encode(
            host = "192.168.1.88",
            port = 47821,
            fingerprint = "0123456789abcdef0123456789abcdef",
            deviceName = "Pixel Tablet",
        )
        assertTrue(uri.startsWith("saltnote://pair?"))
        val parsed = LanPairingCodec.parse(uri)
        assertNotNull(parsed)
        assertEquals("192.168.1.88", parsed!!.host)
        assertEquals(47821, parsed.port)
        assertEquals("0123456789abcdef0123456789abcdef", parsed.fingerprint)
        assertEquals("Pixel Tablet", parsed.deviceName)
        assertNull(LanPairingCodec.parse("https://example.com"))

        // Verify ZXing QR matrix generation with width=0, height=0 produces valid minimal matrix
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 0, 0, hints)
        assertTrue(matrix.width >= 21 && matrix.width == matrix.height)
    }

    @Test
    fun languageResolutionAndMinimalistStringsWork() {
        assertEquals(AppLanguage.ZhCn, resolveAppLanguage("system", "zh-CN"))
        assertEquals(AppLanguage.ZhTw, resolveAppLanguage("system", "zh-TW"))
        assertEquals(AppLanguage.ZhTw, resolveAppLanguage("system", "zh-Hant-HK"))
        assertEquals(AppLanguage.Ja, resolveAppLanguage("system", "ja-JP"))
        assertEquals(AppLanguage.Ko, resolveAppLanguage("system", "ko-KR"))
        assertEquals(AppLanguage.En, resolveAppLanguage("system", "fr-FR"))
        assertEquals(AppLanguage.En, resolveAppLanguage("en", "zh-CN"))

        assertEquals("zh-TW", resolveAiOutputLanguageCode("follow_ui", "system", "zh-TW"))
        assertEquals("ja", resolveAiOutputLanguageCode("follow_ui", "ja", "en-US"))
        assertEquals("en", resolveAiOutputLanguageCode("en", "zh-CN", "zh-CN"))

        val enStrings = stringsFor(AppLanguage.En)
        assertEquals("Settings", enStrings.settingsTitle)
        assertEquals("API Key", enStrings.apiKeyLabel)
        assertEquals("LAN Sync", enStrings.lanSyncTitle)

        val zhStrings = stringsFor(AppLanguage.ZhCn)
        assertEquals("设置", zhStrings.settingsTitle)
        assertEquals("API Key", zhStrings.apiKeyLabel)
        assertEquals("局域网同步", zhStrings.lanSyncTitle)
    }
}
