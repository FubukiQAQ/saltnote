package com.saltnote.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Full-featured Material Design 3 Markdown renderer supporting headings, nested unordered/ordered lists,
 * blockquotes, fenced code blocks, dividers, and inline bold/italic/strikethrough/code/links.
 */
@Composable
fun MarkdownLinesPreview(lines: List<String>) {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val codeFg = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary

    val blocks = remember(lines) { parseMarkdownBlocks(lines) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineMedium
                        2 -> MaterialTheme.typography.headlineSmall
                        3 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }
                    val color = if (block.level == 1) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        text = parseInlineMarkdown(block.text, codeBg, codeFg, linkColor),
                        style = style,
                        color = color,
                        modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp),
                    )
                }

                is MdBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(block.text, codeBg, codeFg, linkColor),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                is MdBlock.BulletItem -> {
                    val indentDp = (block.indentLevel * 18 + 6).dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = indentDp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = if (block.indentLevel == 0) "•" else "◦",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(16.dp),
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, codeBg, codeFg, linkColor),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.OrderedItem -> {
                    val indentDp = (block.indentLevel * 18).dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = indentDp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "${block.number}.",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, codeBg, codeFg, linkColor),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.Blockquote -> {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
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
                            Text(
                                text = parseInlineMarkdown(block.text, codeBg, codeFg, linkColor),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                is MdBlock.CodeBlock -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = block.code,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                MdBlock.Divider -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                MdBlock.SpacerBlock -> Spacer(Modifier.height(2.dp))
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BulletItem(val indentLevel: Int, val text: String) : MdBlock
    data class OrderedItem(val indentLevel: Int, val number: String, val text: String) : MdBlock
    data class Blockquote(val text: String) : MdBlock
    data class CodeBlock(val lang: String, val code: String) : MdBlock
    data object Divider : MdBlock
    data object SpacerBlock : MdBlock
}

private val orderedItemRegex = Regex("""^(\d+)\.\s+(.*)$""")
private val bulletItemRegex = Regex("""^[-*+]\s+(.*)$""")

private fun parseMarkdownBlocks(lines: List<String>): List<MdBlock> {
    val result = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val leadingSpaces = raw.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4 else 1 }
        val indentLevel = (leadingSpaces / 2).coerceAtMost(4)
        val trimmed = raw.trim()

        if (trimmed.isEmpty()) {
            if (result.lastOrNull() != MdBlock.SpacerBlock) {
                result.add(MdBlock.SpacerBlock)
            }
            i++
            continue
        }

        // Fenced code block
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            result.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            i++
            continue
        }

        // Divider
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            result.add(MdBlock.Divider)
            i++
            continue
        }

        // Headings
        val headingMatch = Regex("""^(#{1,4})\s+(.*)$""").find(trimmed)
        if (headingMatch != null) {
            result.add(MdBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
            i++
            continue
        }

        // Blockquotes (group consecutive `>` lines)
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines.add(lines[i].trim().removePrefix(">").trimStart())
                i++
            }
            result.add(MdBlock.Blockquote(quoteLines.joinToString("\n")))
            continue
        }

        // Unordered list item (including task list `[ ]` / `[x]`)
        val bullet = bulletItemRegex.find(trimmed)
        if (bullet != null) {
            val content = bullet.groupValues[1]
                .replace(Regex("""^\[x\]\s+""", RegexOption.IGNORE_CASE), "☑ ")
                .replace(Regex("""^\[\s\]\s+"""), "☐ ")
            result.add(MdBlock.BulletItem(indentLevel, content))
            i++
            continue
        }

        // Ordered list item
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

/**
 * Parses inline Markdown spans (`**bold**`, `__bold__`, `*italic*`, `~~strike~~`, `` `code` ``, `[label](url)`).
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
        // Inline code: `...`
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

        // Bold: **...** or __...__
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

        // Strikethrough: ~~...~~
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

        // Italic: *...*
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

        // Link: [label](url)
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
