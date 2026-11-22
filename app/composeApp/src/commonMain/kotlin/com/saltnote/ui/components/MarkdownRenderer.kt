package com.saltnote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saltnote.bridge.FlashcardDraftDto
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.theme.SaltHaloAmber
import com.saltnote.ui.theme.SaltHaloGlacier
import com.saltnote.ui.theme.SaltHaloMineral
import kotlinx.serialization.json.Json

private val markdownJson = Json { ignoreUnknownKeys = true }

/**
 * Convenience composable for rendering a full Markdown + LaTeX string
 * (used in Salt Halo popups, FSRS flashcards, and document split preview).
 */
@Composable
fun MarkdownContent(
    markdown: String,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val lines = remember(markdown) { markdown.lines() }
    MarkdownLinesPreview(lines = lines, compact = compact, modifier = modifier)
}

/**
 * Full-featured Material Design 3 Markdown + LaTeX renderer supporting:
 * - Headings H1..H6, paragraphs, nested unordered/ordered/task lists
 * - Recursive blockquotes & semantic 3-color Salt Halo callouts (both new `[!salt-*]` and legacy `Salt Halo (builtin.*)`)
 * - Legacy glacier flashcard JSON auto-recovery inside fused callouts
 * - Display LaTeX (`$$...$$`, `\[...\]`, ```latex / ```math) and inline LaTeX (`$...$`, `\(...\)`)
 * - GFM pipe tables, fenced code blocks with language badges, horizontal rules
 */
@Composable
fun MarkdownLinesPreview(
    lines: List<String>,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(lines) { parseMarkdownBlocks(lines) }
    MarkdownBlocksColumn(blocks = blocks, compact = compact, modifier = modifier)
}

@Composable
private fun MarkdownBlocksColumn(
    blocks: List<MdBlock>,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val codeFg = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val bodyStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    val spacing = if (compact) 5.dp else 7.dp

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium
                        2 -> if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall
                        3 -> if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }
                    val color = if (block.level == 1) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    SaltRichInlineText(
                        input = block.text,
                        style = style,
                        color = color,
                        codeBg = codeBg,
                        codeFg = codeFg,
                        linkColor = linkColor,
                        modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 2.dp),
                    )
                }

                is MdBlock.Paragraph -> {
                    SaltRichInlineText(
                        input = block.text,
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        codeBg = codeBg,
                        codeFg = codeFg,
                        linkColor = linkColor,
                    )
                }

                is MdBlock.BulletItem -> {
                    val indentDp = (block.indentLevel * 16 + 4).dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = indentDp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = if (block.indentLevel == 0) "•" else "◦",
                            style = bodyStyle,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(16.dp),
                        )
                        SaltRichInlineText(
                            input = block.text,
                            style = bodyStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            codeBg = codeBg,
                            codeFg = codeFg,
                            linkColor = linkColor,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.OrderedItem -> {
                    val indentDp = (block.indentLevel * 16).dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = indentDp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "${block.number}.",
                            style = bodyStyle,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        SaltRichInlineText(
                            input = block.text,
                            style = bodyStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            codeBg = codeBg,
                            codeFg = codeFg,
                            linkColor = linkColor,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.Blockquote -> {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                            MarkdownBlocksColumn(
                                blocks = block.children,
                                compact = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                is MdBlock.Callout -> {
                    CalloutBlockCard(callout = block)
                }

                is MdBlock.MathBlock -> {
                    SaltBlockMath(
                        latex = block.latex,
                        fontSize = if (compact) 16.sp else 18.sp,
                    )
                }

                is MdBlock.CodeBlock -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (block.lang.isNotBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = block.lang.lowercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(12.dp),
                            ) {
                                Text(
                                    text = block.code,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                is MdBlock.Table -> {
                    MarkdownTableCard(
                        table = block,
                        compact = compact,
                        codeBg = codeBg,
                        codeFg = codeFg,
                        linkColor = linkColor,
                    )
                }

                MdBlock.Divider -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                MdBlock.SpacerBlock -> Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun CalloutBlockCard(callout: MdBlock.Callout) {
    val strings = LocalSaltStrings.current
    val accent: Color
    val icon: ImageVector
    val defaultTitle: String
    when (callout.kind) {
        CalloutKind.SaltAmber -> {
            accent = SaltHaloAmber
            icon = Icons.Rounded.AutoAwesome
            defaultTitle = strings.calloutAmberTitle
        }
        CalloutKind.SaltGlacier -> {
            accent = SaltHaloGlacier
            icon = Icons.Rounded.Style
            defaultTitle = strings.calloutGlacierTitle
        }
        CalloutKind.SaltMineral -> {
            accent = SaltHaloMineral
            icon = Icons.Rounded.DocumentScanner
            defaultTitle = strings.calloutMineralTitle
        }
        CalloutKind.Tip -> {
            accent = Color(0xFF2E7D32)
            icon = Icons.Rounded.Lightbulb
            defaultTitle = strings.calloutTipTitle
        }
        CalloutKind.Warning -> {
            accent = Color(0xFFD97706)
            icon = Icons.Rounded.Warning
            defaultTitle = strings.calloutWarningTitle
        }
        CalloutKind.Note -> {
            accent = MaterialTheme.colorScheme.primary
            icon = Icons.Rounded.Info
            defaultTitle = strings.calloutNoteTitle
        }
    }
    val displayTitle = if (callout.hasCustomTitle && callout.title.isNotBlank()) callout.title else defaultTitle

    Surface(
        color = accent.copy(alpha = 0.08f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (callout.legacyCards.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        callout.legacyCards.forEachIndexed { idx, card ->
                            OutlinedCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "Q${idx + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SaltHaloGlacier,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    MarkdownContent(markdown = card.front, compact = true)
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                                    MarkdownContent(markdown = card.back, compact = true)
                                }
                            }
                        }
                    }
                } else if (callout.children.isNotEmpty()) {
                    MarkdownBlocksColumn(
                        blocks = callout.children,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableCard(
    table: MdBlock.Table,
    compact: Boolean,
    codeBg: Color,
    codeFg: Color,
    linkColor: Color,
) {
    val cellStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                table.headers.forEach { header ->
                    SaltRichInlineText(
                        input = header,
                        style = cellStyle.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        codeBg = codeBg,
                        codeFg = codeFg,
                        linkColor = linkColor,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            table.rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (c in 0 until table.headers.size) {
                        val cell = row.getOrElse(c) { "" }
                        SaltRichInlineText(
                            input = cell,
                            style = cellStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            codeBg = codeBg,
                            codeFg = codeFg,
                            linkColor = linkColor,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

internal enum class CalloutKind {
    SaltAmber,
    SaltGlacier,
    SaltMineral,
    Note,
    Tip,
    Warning,
}

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BulletItem(val indentLevel: Int, val text: String) : MdBlock
    data class OrderedItem(val indentLevel: Int, val number: String, val text: String) : MdBlock
    data class Blockquote(val children: List<MdBlock>) : MdBlock
    data class Callout(
        val kind: CalloutKind,
        val title: String,
        val children: List<MdBlock>,
        val legacyCards: List<FlashcardDraftDto> = emptyList(),
        val hasCustomTitle: Boolean = false,
    ) : MdBlock
    data class CodeBlock(val lang: String, val code: String) : MdBlock
    data class MathBlock(val latex: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data object Divider : MdBlock
    data object SpacerBlock : MdBlock
}

private val headingRegex = Regex("""^(#{1,6})\s+(.*)$""")
private val orderedItemRegex = Regex("""^(\d+)\.\s+(.*)$""")
private val bulletItemRegex = Regex("""^[-*+]\s+(.*)$""")
private val calloutHeaderRegex = Regex("""^\[!([a-zA-Z0-9_-]+)\]\s*(.*)$""")
private val legacyHaloHeaderRegex = Regex("""^\*\*Salt\s+Halo\s*\(([^)]+)\)\*\*\s*$""", RegexOption.IGNORE_CASE)
private val tableSeparatorRegex = Regex("""^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$""")

internal fun parseMarkdownBlocks(lines: List<String>): List<MdBlock> {
    val result = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val leadingSpaces = raw.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4 else 1 }
        val indentLevel = (leadingSpaces / 2).coerceAtMost(4)
        val trimmed = raw.trim()

        if (trimmed.isEmpty()) {
            if (result.lastOrNull() != MdBlock.SpacerBlock && result.isNotEmpty()) {
                result.add(MdBlock.SpacerBlock)
            }
            i++
            continue
        }

        // 1. Display Math Block: `$$ ... $$` (single-line or multi-line)
        if (trimmed.startsWith("$$")) {
            val afterOpen = trimmed.removePrefix("$$").trim()
            if (afterOpen.endsWith("$$") && afterOpen.length >= 2) {
                val expr = afterOpen.removeSuffix("$$").trim()
                if (expr.isNotEmpty()) {
                    result.add(MdBlock.MathBlock(expr))
                }
                i++
                continue
            }
            val mathLines = mutableListOf<String>()
            if (afterOpen.isNotEmpty()) mathLines.add(afterOpen)
            i++
            while (i < lines.size) {
                val cur = lines[i].trim()
                if (cur.endsWith("$$")) {
                    val beforeClose = cur.removeSuffix("$$").trim()
                    if (beforeClose.isNotEmpty()) mathLines.add(beforeClose)
                    i++
                    break
                }
                mathLines.add(lines[i])
                i++
            }
            val joined = mathLines.joinToString("\n").trim()
            if (joined.isNotEmpty()) result.add(MdBlock.MathBlock(joined))
            continue
        }

        // 2. Display Math Block: `\[ ... \]` (single-line or multi-line)
        if (trimmed.startsWith("\\[")) {
            val afterOpen = trimmed.removePrefix("\\[").trim()
            if (afterOpen.endsWith("\\]") && afterOpen.length >= 2) {
                val expr = afterOpen.removeSuffix("\\]").trim()
                if (expr.isNotEmpty()) result.add(MdBlock.MathBlock(expr))
                i++
                continue
            }
            val mathLines = mutableListOf<String>()
            if (afterOpen.isNotEmpty()) mathLines.add(afterOpen)
            i++
            while (i < lines.size) {
                val cur = lines[i].trim()
                if (cur.endsWith("\\]")) {
                    val beforeClose = cur.removeSuffix("\\]").trim()
                    if (beforeClose.isNotEmpty()) mathLines.add(beforeClose)
                    i++
                    break
                }
                mathLines.add(lines[i])
                i++
            }
            val joined = mathLines.joinToString("\n").trim()
            if (joined.isNotEmpty()) result.add(MdBlock.MathBlock(joined))
            continue
        }

        // 3. Fenced Code Block (including ```latex / ```math / ```tex rendered as MathBlock!)
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            val code = codeLines.joinToString("\n")
            val lowerLang = lang.lowercase()
            if (lowerLang == "latex" || lowerLang == "math" || lowerLang == "tex") {
                result.add(MdBlock.MathBlock(code.trim()))
            } else {
                result.add(MdBlock.CodeBlock(lang, code))
            }
            if (i < lines.size) i++
            continue
        }

        // 4. Divider
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            result.add(MdBlock.Divider)
            i++
            continue
        }

        // 5. Headings H1..H6
        val headingMatch = headingRegex.find(trimmed)
        if (headingMatch != null) {
            result.add(MdBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
            i++
            continue
        }

        // 6. Blockquotes & Semantic Salt Halo Callouts (recursive!)
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                val stripped = lines[i].trimStart().removePrefix(">").let { rest ->
                    if (rest.startsWith(" ")) rest.substring(1) else rest
                }
                quoteLines.add(stripped)
                i++
            }
            result.add(buildQuoteOrCallout(quoteLines))
            continue
        }

        // 7. GFM Pipe Table
        if (trimmed.contains("|") && i + 1 < lines.size && tableSeparatorRegex.matches(lines[i + 1].trim())) {
            val headers = splitTableRow(trimmed)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().contains("|") && lines[i].trim().isNotEmpty()) {
                rows.add(splitTableRow(lines[i].trim()))
                i++
            }
            if (headers.isNotEmpty()) {
                result.add(MdBlock.Table(headers, rows))
                continue
            }
        }

        // 8. Unordered list item (including task list `[ ]` / `[x]`)
        val bullet = bulletItemRegex.find(trimmed)
        if (bullet != null) {
            val content = bullet.groupValues[1]
                .replace(Regex("""^\[x\]\s+""", RegexOption.IGNORE_CASE), "☑ ")
                .replace(Regex("""^\[\s\]\s+"""), "☐ ")
            result.add(MdBlock.BulletItem(indentLevel, content))
            i++
            continue
        }

        // 9. Ordered list item
        val ordered = orderedItemRegex.find(trimmed)
        if (ordered != null) {
            result.add(MdBlock.OrderedItem(indentLevel, ordered.groupValues[1], ordered.groupValues[2]))
            i++
            continue
        }

        result.add(MdBlock.Paragraph(trimmed))
        i++
    }
    return result
}

private fun buildQuoteOrCallout(quoteLines: List<String>): MdBlock {
    val firstNonEmptyIdx = quoteLines.indexOfFirst { it.isNotBlank() }
    if (firstNonEmptyIdx < 0) {
        return MdBlock.Blockquote(emptyList())
    }
    val firstLine = quoteLines[firstNonEmptyIdx].trim()
    val restLines = quoteLines.drop(firstNonEmptyIdx + 1)

    // Pattern A: New semantic callout `[!salt-amber] ...`, `[!NOTE] ...`, etc.
    val calloutMatch = calloutHeaderRegex.find(firstLine)
    if (calloutMatch != null) {
        val tag = calloutMatch.groupValues[1].lowercase()
        val customTitle = calloutMatch.groupValues[2].trim()
        val (kind, defaultTitle) = when (tag) {
            "salt-amber", "amber" -> CalloutKind.SaltAmber to "琥珀黄 · 概念扩充"
            "salt-glacier", "glacier" -> CalloutKind.SaltGlacier to "冰川蓝 · 提炼闪卡"
            "salt-mineral", "mineral" -> CalloutKind.SaltMineral to "矿石灰 · 笔迹清洗 OCR"
            "tip" -> CalloutKind.Tip to "提示"
            "warning", "caution" -> CalloutKind.Warning to "注意"
            else -> CalloutKind.Note to "批注"
        }
        val legacyCards = if (kind == CalloutKind.SaltGlacier) {
            tryExtractLegacyFlashcards(restLines.joinToString("\n"))
        } else {
            emptyList()
        }
        return MdBlock.Callout(
            kind = kind,
            title = customTitle.ifBlank { defaultTitle },
            children = if (legacyCards.isEmpty()) parseMarkdownBlocks(restLines) else emptyList(),
            legacyCards = legacyCards,
            hasCustomTitle = customTitle.isNotBlank(),
        )
    }

    // Pattern B: Legacy v0.1-alpha format `**Salt Halo (builtin.amber_expand)**`
    val legacyMatch = legacyHaloHeaderRegex.find(firstLine)
    if (legacyMatch != null) {
        val presetId = legacyMatch.groupValues[1].trim().lowercase()
        val isBuiltin = presetId.contains("amber") || presetId.contains("glacier") || presetId.contains("mineral")
        val (kind, title) = when {
            presetId.contains("amber") -> CalloutKind.SaltAmber to "琥珀黄 · 概念扩充"
            presetId.contains("glacier") -> CalloutKind.SaltGlacier to "冰川蓝 · 提炼闪卡"
            presetId.contains("mineral") -> CalloutKind.SaltMineral to "矿石灰 · 笔迹清洗 OCR"
            else -> CalloutKind.SaltAmber to "Salt Halo · $presetId"
        }
        val legacyCards = if (kind == CalloutKind.SaltGlacier) {
            tryExtractLegacyFlashcards(restLines.joinToString("\n"))
        } else {
            emptyList()
        }
        return MdBlock.Callout(
            kind = kind,
            title = title,
            children = if (legacyCards.isEmpty()) parseMarkdownBlocks(restLines) else emptyList(),
            legacyCards = legacyCards,
            hasCustomTitle = !isBuiltin,
        )
    }

    // Standard recursive blockquote (supports headings, lists, code blocks, LaTeX, nested quotes!)
    return MdBlock.Blockquote(parseMarkdownBlocks(quoteLines))
}

private fun tryExtractLegacyFlashcards(raw: String): List<FlashcardDraftDto> {
    val trimmed = raw.trim()
    val start = trimmed.indexOf('[')
    val end = trimmed.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    val candidate = trimmed.substring(start, end + 1)
    return runCatching {
        markdownJson.decodeFromString<List<FlashcardDraftDto>>(candidate)
            .filter { it.front.isNotBlank() && it.back.isNotBlank() }
    }.getOrDefault(emptyList())
}

private fun splitTableRow(line: String): List<String> =
    line.trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split("|")
        .map { it.trim() }

/**
 * Pure non-math inline Markdown span parser (`**bold**`, `__bold__`, `*italic*`, `~~strike~~`, `` `code` ``, `[label](url)`).
 */
fun parseInlineMarkdown(
    input: String,
    codeBg: Color,
    codeFg: Color,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = input.length
    while (i < n) {
        if (input[i] == '`') {
            val end = input.indexOf('`', i + 1)
            if (end > i + 1) {
                val content = input.substring(i + 1, end)
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg,
                        color = codeFg,
                    ),
                )
                append(" $content ")
                pop()
                i = end + 1
                continue
            }
        }

        if (i + 1 < n && ((input[i] == '*' && input[i + 1] == '*') || (input[i] == '_' && input[i + 1] == '_'))) {
            val delim = input.substring(i, i + 2)
            val end = input.indexOf(delim, i + 2)
            if (end > i + 2) {
                val inner = input.substring(i + 2, end)
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(parseInlineMarkdown(inner, codeBg, codeFg, linkColor))
                pop()
                i = end + 2
                continue
            }
        }

        if (i + 1 < n && input[i] == '~' && input[i + 1] == '~') {
            val end = input.indexOf("~~", i + 2)
            if (end > i + 2) {
                val inner = input.substring(i + 2, end)
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(parseInlineMarkdown(inner, codeBg, codeFg, linkColor))
                pop()
                i = end + 2
                continue
            }
        }

        if (input[i] == '*' && (i + 1 >= n || input[i + 1] != ' ')) {
            val end = input.indexOf('*', i + 1)
            if (end > i + 1 && input[end - 1] != ' ') {
                val inner = input.substring(i + 1, end)
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(parseInlineMarkdown(inner, codeBg, codeFg, linkColor))
                pop()
                i = end + 1
                continue
            }
        }

        if (input[i] == '[') {
            val closeBracket = input.indexOf(']', i + 1)
            if (closeBracket > i + 1 && closeBracket + 1 < n && input[closeBracket + 1] == '(') {
                val closeParen = input.indexOf(')', closeBracket + 2)
                if (closeParen > closeBracket + 2) {
                    val label = input.substring(i + 1, closeBracket)
                    pushStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    )
                    append(label)
                    pop()
                    i = closeParen + 1
                    continue
                }
            }
        }

        append(input[i])
        i++
    }
}
