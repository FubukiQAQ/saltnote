package com.saltnote.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme

/**
 * Strips outer LaTeX delimiters (`$$...$$`, `\[...\]`, `$...$`, `\(...\)`) cleanly.
 */
fun stripMathDelimiters(raw: String): String {
    val t = raw.trim()
    return when {
        t.startsWith("$$") && t.endsWith("$$") && t.length > 4 -> t.substring(2, t.length - 2).trim()
        t.startsWith("\\[") && t.endsWith("\\]") && t.length > 4 -> t.substring(2, t.length - 2).trim()
        t.startsWith("\\(") && t.endsWith("\\)") && t.length > 4 -> t.substring(2, t.length - 2).trim()
        t.startsWith("$") && t.endsWith("$") && t.length > 2 -> t.substring(1, t.length - 1).trim()
        else -> t
    }
}

/**
 * Checks whether a LaTeX fragment has balanced braces before handing to renderer
 * (prevents flickering or partial-token distortion while AI streams tokens).
 */
private fun isLikelyCompleteLatex(expr: String): Boolean {
    if (expr.isBlank()) return false
    var depth = 0
    var escaped = false
    for (ch in expr) {
        if (escaped) {
            escaped = false
            continue
        }
        when (ch) {
            '\\' -> escaped = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth < 0) return false
            }
        }
    }
    return depth == 0 && !escaped
}

/**
 * Native Compose Multiplatform display/block LaTeX formula renderer backed by `latex-renderer` + KaTeX fonts.
 * Supports dark/light Material 3 themes, horizontal scrolling for wide equations, and graceful streaming fallback.
 */
@Composable
fun SaltBlockMath(
    latex: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = 18.sp,
) {
    val clean = remember(latex) { stripMathDelimiters(latex) }
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val theme = remember(textColor, isDark) {
        if (isDark) {
            LatexTheme.dark(color = textColor, backgroundColor = Color.Transparent)
        } else {
            LatexTheme.light(color = textColor, backgroundColor = Color.Transparent)
        }
    }
    val config = remember(fontSize, theme) {
        LatexConfig(
            fontSize = fontSize,
            theme = theme,
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.65f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isLikelyCompleteLatex(clean)) {
                Latex(
                    latex = clean,
                    config = config,
                    isDarkTheme = isDark,
                )
            } else {
                Text(
                    text = clean,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = textColor.copy(alpha = 0.85f),
                )
            }
        }
    }
}

private data class ParsedInlineResult(
    val annotated: AnnotatedString,
    val mathFormulas: List<Pair<String, String>>, // (inlineId, latexExpr)
)

/**
 * Renders rich inline Markdown (`**bold**`, `*italic*`, `~~strike~~`, `` `code` ``, `[label](url)`)
 * seamlessly mixed with inline LaTeX math (`$...$` and `\(...\)`) using `LatexMeasurerState.inlineContent`.
 */
@Composable
fun SaltRichInlineText(
    input: String,
    style: TextStyle,
    color: Color,
    codeBg: Color,
    codeFg: Color,
    linkColor: Color,
    modifier: Modifier = Modifier,
) {
    val hasPossibleMath = remember(input) {
        input.indexOf('$') >= 0 || input.contains("\\(")
    }

    if (!hasPossibleMath) {
        val plain = remember(input, codeBg, codeFg, linkColor) {
            parseInlineMarkdown(input, codeBg, codeFg, linkColor)
        }
        Text(
            text = plain,
            style = style,
            color = color,
            modifier = modifier,
        )
        return
    }

    val parsed = remember(input, codeBg, codeFg, linkColor) {
        parseInlineMarkdownWithMath(input, codeBg, codeFg, linkColor)
    }

    if (parsed.mathFormulas.isEmpty()) {
        Text(
            text = parsed.annotated,
            style = style,
            color = color,
            modifier = modifier,
        )
        return
    }

    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val mathFontSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize else 15.sp
    val theme = remember(color, isDark) {
        if (isDark) {
            LatexTheme.dark(color = color, backgroundColor = Color.Transparent)
        } else {
            LatexTheme.light(color = color, backgroundColor = Color.Transparent)
        }
    }
    val config = remember(mathFontSize, theme) {
        LatexConfig(
            fontSize = mathFontSize,
            theme = theme,
        )
    }
    val measurer = rememberLatexMeasurer(config)
    val inlineMap = remember(parsed.mathFormulas, config, isDark, measurer) {
        val map = LinkedHashMap<String, InlineTextContent>(parsed.mathFormulas.size)
        for ((id, expr) in parsed.mathFormulas) {
            if (isLikelyCompleteLatex(expr)) {
                runCatching {
                    measurer.inlineContent(
                        latex = expr,
                        config = config,
                        isDarkTheme = isDark,
                    )
                }.getOrNull()?.let { content ->
                    map[id] = content
                }
            }
        }
        map
    }

    Text(
        text = parsed.annotated,
        inlineContent = inlineMap,
        style = style,
        color = color,
        modifier = modifier,
    )
}

private fun parseInlineMarkdownWithMath(
    input: String,
    codeBg: Color,
    codeFg: Color,
    linkColor: Color,
): ParsedInlineResult {
    val formulas = mutableListOf<Pair<String, String>>()
    val annotated = buildAnnotatedString {
        appendInlineMarkdownRecursive(
            input = input,
            codeBg = codeBg,
            codeFg = codeFg,
            linkColor = linkColor,
            formulas = formulas,
        )
    }
    return ParsedInlineResult(annotated, formulas)
}

private fun AnnotatedString.Builder.appendInlineMarkdownRecursive(
    input: String,
    codeBg: Color,
    codeFg: Color,
    linkColor: Color,
    formulas: MutableList<Pair<String, String>>,
) {
    var i = 0
    val n = input.length
    while (i < n) {
        // Escaped dollar `\$` -> literal `$`
        if (input[i] == '\\' && i + 1 < n && input[i + 1] == '$') {
            append('$')
            i += 2
            continue
        }

        // Inline code: `...` (do NOT parse LaTeX inside backticks)
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

        // Inline LaTeX: \( ... \)
        if (input[i] == '\\' && i + 1 < n && input[i + 1] == '(') {
            val end = input.indexOf("\\)", i + 2)
            if (end > i + 2) {
                val expr = input.substring(i + 2, end).trim()
                if (expr.isNotEmpty()) {
                    val id = "math_${formulas.size}"
                    formulas.add(id to expr)
                    appendInlineContent(id = id, alternateText = expr)
                    i = end + 2
                    continue
                }
            }
        }

        // Inline LaTeX: $$ ... $$ embedded within a line OR $ ... $
        if (input[i] == '$') {
            val isDouble = i + 1 < n && input[i + 1] == '$'
            if (isDouble) {
                val end = input.indexOf("$$", i + 2)
                if (end > i + 2) {
                    val expr = input.substring(i + 2, end).trim()
                    if (expr.isNotEmpty()) {
                        val id = "math_${formulas.size}"
                        formulas.add(id to expr)
                        appendInlineContent(id = id, alternateText = expr)
                        i = end + 2
                        continue
                    }
                }
            } else {
                val end = findClosingSingleDollar(input, i + 1)
                if (end > i + 1) {
                    val expr = input.substring(i + 1, end).trim()
                    if (expr.isNotEmpty()) {
                        val id = "math_${formulas.size}"
                        formulas.add(id to expr)
                        appendInlineContent(id = id, alternateText = expr)
                        i = end + 1
                        continue
                    }
                }
            }
        }

        // Bold: **...** or __...__
        if (i + 1 < n && ((input[i] == '*' && input[i + 1] == '*') || (input[i] == '_' && input[i + 1] == '_'))) {
            val delim = input.substring(i, i + 2)
            val end = input.indexOf(delim, i + 2)
            if (end > i + 2) {
                val inner = input.substring(i + 2, end)
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendInlineMarkdownRecursive(inner, codeBg, codeFg, linkColor, formulas)
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
                appendInlineMarkdownRecursive(inner, codeBg, codeFg, linkColor, formulas)
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
                appendInlineMarkdownRecursive(inner, codeBg, codeFg, linkColor, formulas)
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

/**
 * Finds the matching closing `$` for inline math while avoiding false positives on currency (`$100`).
 */
private fun findClosingSingleDollar(input: String, startIdx: Int): Int {
    val n = input.length
    if (startIdx >= n || input[startIdx].isWhitespace()) return -1
    var j = startIdx
    while (j < n) {
        val ch = input[j]
        if (ch == '\\' && j + 1 < n) {
            j += 2
            continue
        }
        if (ch == '\n') return -1
        if (ch == '$') {
            // Avoid matching `$$` as a single closing dollar
            if (j + 1 < n && input[j + 1] == '$') return -1
            if (j > startIdx && !input[j - 1].isWhitespace()) {
                val content = input.substring(startIdx, j)
                // Guard against prose currency spans like "$10 and $20"
                val looksLikeCurrency = content.firstOrNull()?.isDigit() == true &&
                    content.contains(' ') &&
                    !content.contains('\\') &&
                    !content.contains('^') &&
                    !content.contains('_') &&
                    !content.contains('=')
                if (!looksLikeCurrency) {
                    return j
                }
            }
            return -1
        }
        j++
    }
    return -1
}
