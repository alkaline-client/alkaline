package alkaline.ui.hud

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/** Minecraft's 16 text colors (EnumChatFormatting), indexed by their § code. */
private val ChatColors = longArrayOf(
    0xFF000000, 0xFF0000AA, 0xFF00AA00, 0xFF00AAAA, 0xFFAA0000, 0xFFAA00AA, 0xFFFFAA00, 0xFFAAAAAA,
    0xFF555555, 0xFF5555FF, 0xFF55FF55, 0xFF55FFFF, 0xFFFF5555, 0xFFFF55FF, 0xFFFFFF55, 0xFFFFFFFF,
)

/** Removes § formatting codes. */
fun String.stripFormatting(): String = replace(Regex("§."), "")

/**
 * Text with § codes as styled spans: colors 0-f (a color resets the other formats, as in vanilla), l bold, o
 * italic, n underline, m strikethrough, r reset; k (obfuscated) is shown plainly.
 */
fun formatted(text: String, base: Color): AnnotatedString = buildAnnotatedString {
    var color = base
    var bold = false
    var italic = false
    var underline = false
    var strike = false
    var index = 0
    val run = StringBuilder()
    fun flush() {
        if (run.isEmpty()) return
        val decoration = when {
            underline && strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            underline -> TextDecoration.Underline
            strike -> TextDecoration.LineThrough
            else -> null
        }
        withStyle(
            SpanStyle(
                color = color,
                fontWeight = if (bold) FontWeight.Bold else null,
                fontStyle = if (italic) FontStyle.Italic else null,
                textDecoration = decoration,
            ),
        ) { append(run.toString()) }
        run.clear()
    }
    while (index < text.length) {
        val character = text[index]
        if (character == '§' && index + 1 < text.length) {
            flush()
            val code = text[index + 1].lowercaseChar()
            val colorIndex = "0123456789abcdef".indexOf(code)
            when {
                colorIndex >= 0 -> {
                    color = Color(ChatColors[colorIndex])
                    bold = false; italic = false; underline = false; strike = false
                }
                code == 'l' -> bold = true
                code == 'o' -> italic = true
                code == 'n' -> underline = true
                code == 'm' -> strike = true
                code == 'r' -> {
                    color = base
                    bold = false; italic = false; underline = false; strike = false
                }
            }
            index += 2
            continue
        }
        run.append(character)
        index++
    }
    flush()
}
