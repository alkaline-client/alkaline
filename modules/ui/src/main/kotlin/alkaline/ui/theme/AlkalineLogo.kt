package alkaline.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Alkaline mark from `generic/logo/alkaline.svg`: two slanted strokes forming an open "A". The viewport is
 * cropped to the glyph's bounds (the source canvas is 1024 square with generous padding) so layouts size the
 * mark itself; tint it with the theme color at the call site.
 */
object AlkalineLogo {
    private const val LEFT = 333f
    private const val TOP = 292f
    private const val WIDTH = 652.5f - LEFT
    private const val HEIGHT = 750.286f - TOP

    /** Width over height of the mark. */
    const val ASPECT = WIDTH / HEIGHT

    val Mark: ImageVector = ImageVector.Builder("AlkalineMark", (24f * ASPECT).dp, 24.dp, WIDTH, HEIGHT).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(558.8f - LEFT, 292f - TOP)
            lineTo(568f - LEFT, 292f - TOP)
            lineTo(568f - LEFT, 414.571f - TOP)
            lineTo(333f - LEFT, 750.286f - TOP)
            lineTo(333f - LEFT, 614.571f - TOP)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(574f - LEFT, 292f - TOP)
            lineTo(652.5f - LEFT, 292f - TOP)
            lineTo(652.5f - LEFT, 638.143f - TOP)
            lineTo(574f - LEFT, 750.286f - TOP)
            close()
        }
    }.build()
}
