package alkaline.ui.menu

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** The sixteen legacy chat colors, sRGB, indexed by their hex digit. */
private val LEGACY_COLORS = longArrayOf(
    0xFF000000, 0xFF0000AA, 0xFF00AA00, 0xFF00AAAA, 0xFFAA0000, 0xFFAA00AA, 0xFFFFAA00, 0xFFAAAAAA,
    0xFF555555, 0xFF5555FF, 0xFF55FF55, 0xFF55FFFF, 0xFFFF5555, 0xFFFF55FF, 0xFFFFFF55, 0xFFFFFFFF,
)

/**
 * Converts {@code §}-formatted text (server MOTDs) to styled text. Colors are mapped onto the theme where the
 * legacy palette assumes a dark backdrop: white and the grays follow [foreground]/[muted], and on light themes
 * bright colors are darkened so yellow and aqua stay legible. Obfuscated text is shown as is, and the spaces
 * servers use to center lines are dropped.
 */
internal fun formatLegacy(text: String, foreground: Color, muted: Color, dark: Boolean): AnnotatedString = buildAnnotatedString {
    var color = foreground
    var bold = false
    var italic = false
    var underline = false
    var strike = false
    val run = StringBuilder()

    fun flush() {
        if (run.isEmpty()) return
        val decorations = listOfNotNull(
            TextDecoration.Underline.takeIf { underline },
            TextDecoration.LineThrough.takeIf { strike },
        )
        pushStyle(
            SpanStyle(
                color = color,
                fontWeight = if (bold) FontWeight.Bold else null,
                fontStyle = if (italic) FontStyle.Italic else null,
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
            ),
        )
        append(run.toString())
        pop()
        run.setLength(0)
    }

    // Servers pad lines with spaces to center them in vanilla's list; cards align left, so drop leading spaces.
    var lineStart = true
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '§' && index + 1 < text.length) {
            val code = text[index + 1].lowercaseChar()
            val digit = "0123456789abcdef".indexOf(code)
            flush()
            when {
                digit >= 0 -> {
                    color = themed(digit, foreground, muted, dark)
                    bold = false; italic = false; underline = false; strike = false
                }
                code == 'l' -> bold = true
                code == 'o' -> italic = true
                code == 'n' -> underline = true
                code == 'm' -> strike = true
                code == 'r' -> {
                    color = foreground
                    bold = false; italic = false; underline = false; strike = false
                }
            }
            index += 2
            continue
        }
        if (char == '\n') {
            lineStart = true
        } else if (lineStart && char == ' ') {
            index++
            continue
        } else {
            lineStart = false
        }
        run.append(char)
        index++
    }
    flush()
}

private fun themed(digit: Int, foreground: Color, muted: Color, dark: Boolean): Color = when (digit) {
    15 -> foreground
    7 -> muted
    8 -> muted.copy(alpha = 0.7f)
    else -> {
        val color = Color(LEGACY_COLORS[digit].toInt())
        if (dark) color else lerp(color, Color.Black, 0.35f)
    }
}

/** Strips {@code §} codes, for search and accessibility text. */
internal fun stripLegacy(text: String): String = text.replace(Regex("§."), "")
