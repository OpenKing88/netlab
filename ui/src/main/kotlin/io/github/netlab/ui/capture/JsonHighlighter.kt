package io.github.netlab.ui.capture

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * JSON 美化与高亮。
 *
 * <p>相比 Monitor 的实现改了一处关键点：**不再在组合期逐字符跑全量 body**。
 * Monitor 把高亮放在 `remember` 里同步执行，body 上限又是 1MB，大响应必然卡住主线程；
 * 这里由调用方在后台线程算好再交给 UI，并且有硬上限。
 */
internal object JsonHighlighter {

    /** 超过这个长度就不再美化，直接当普通文本显示。 */
    const val MAX_PRETTY_CHARS = 256 * 1024

    /** 高亮最多处理这么多行，剩下的原样追加。 */
    private const val MAX_HIGHLIGHT_LINES = 2_000

    fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    fun prettyPrint(text: String): String {
        if (text.length > MAX_PRETTY_CHARS || !looksLikeJson(text)) {
            return text
        }
        val builder = StringBuilder(text.length + 64)
        var indent = 0
        var inString = false
        var escaped = false
        for (char in text) {
            when {
                inString -> {
                    builder.append(char)
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> inString = false
                    }
                }

                char == '"' -> {
                    inString = true
                    builder.append(char)
                }

                char == '{' || char == '[' -> {
                    indent++
                    builder.append(char).append('\n').append(indent(indent))
                }

                char == '}' || char == ']' -> {
                    indent = (indent - 1).coerceAtLeast(0)
                    builder.append('\n').append(indent(indent)).append(char)
                }

                char == ',' -> builder.append(char).append('\n').append(indent(indent))
                char == ':' -> builder.append(": ")
                char == ' ' || char == '\n' || char == '\r' || char == '\t' -> Unit
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }

    fun highlight(
        json: String,
        keyColor: Color,
        valueColor: Color,
        literalColor: Color,
        punctuationColor: Color,
    ): AnnotatedString {
        if (!looksLikeJson(json)) {
            return AnnotatedString(json)
        }
        val limited = limitLines(json)
        return buildAnnotatedString {
            var index = 0
            while (index < limited.length) {
                val char = limited[index]
                when {
                    char == '"' -> {
                        val end = endOfString(limited, index)
                        var cursor = end
                        while (cursor < limited.length && limited[cursor].isWhitespace()) {
                            cursor++
                        }
                        val isKey = cursor < limited.length && limited[cursor] == ':'
                        withStyle(SpanStyle(color = if (isKey) keyColor else valueColor)) {
                            append(limited.substring(index, end))
                        }
                        index = end
                    }

                    char.isDigit() || char == '-' -> {
                        val end = endOfNumber(limited, index)
                        withStyle(SpanStyle(color = literalColor)) {
                            append(limited.substring(index, end))
                        }
                        index = end
                    }

                    char.isLetter() -> {
                        val end = endOfWord(limited, index)
                        withStyle(SpanStyle(color = literalColor)) {
                            append(limited.substring(index, end))
                        }
                        index = end
                    }

                    else -> {
                        withStyle(SpanStyle(color = punctuationColor)) {
                            append(char)
                        }
                        index++
                    }
                }
            }
        }
    }

    private fun limitLines(text: String): String {
        var lines = 0
        for (index in text.indices) {
            if (text[index] == '\n') {
                lines++
                if (lines >= MAX_HIGHLIGHT_LINES) {
                    return text.substring(0, index)
                }
            }
        }
        return text
    }

    private fun endOfString(text: String, start: Int): Int {
        var index = start + 1
        var escaped = false
        while (index < text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> return index + 1
            }
            index++
        }
        return text.length
    }

    private fun endOfNumber(text: String, start: Int): Int {
        var index = start
        while (index < text.length) {
            val char = text[index]
            if (!char.isDigit() && char != '.' && char != '-' && char != '+' && char != 'e' && char != 'E') {
                break
            }
            index++
        }
        return index
    }

    private fun endOfWord(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isLetter()) {
            index++
        }
        return index
    }

    private fun indent(level: Int): String = "  ".repeat(level.coerceAtMost(32))
}
