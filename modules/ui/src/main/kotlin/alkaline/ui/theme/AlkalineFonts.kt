package alkaline.ui.theme

import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Google Sans Flex (SIL OFL 1.1, bundled with its license under `alkaline/ui/fonts`). One variable file
 * supplies every weight; each family below pins the optical size (`opsz`) for the text sizes it serves, so
 * small labels get open, sturdy shapes and large headings get tighter display shapes. Terminals stay square
 * (`ROND` 0): the rounded variant reads as playful, which is not Alkaline's voice.
 */
object AlkalineFonts {
    private const val RESOURCE = "alkaline/ui/fonts/GoogleSansFlex.ttf"
    private val WEIGHTS = listOf(
        FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold,
        FontWeight.Black,
    )

    /** Read once; Skia keeps its own typeface per variation instance. */
    private val bytes: ByteArray by lazy {
        val stream = AlkalineFonts::class.java.classLoader.getResourceAsStream(RESOURCE)
            ?: error("Missing bundled font $RESOURCE")
        stream.use { it.readBytes() }
    }

    /** Labels and body copy (11–16 sp). */
    val text: FontFamily by lazy { family("text", opticalSize = 14f) }

    /** Titles and headlines (18–32 sp). */
    val title: FontFamily by lazy { family("title", opticalSize = 24f) }

    /** Display and the wordmark (36 sp and up). */
    val display: FontFamily by lazy { family("display", opticalSize = 72f) }

    private fun family(role: String, opticalSize: Float): FontFamily = FontFamily(
        WEIGHTS.map { weight ->
            Font(
                identity = "GoogleSansFlex-$role-${weight.weight}",
                data = bytes,
                weight = weight,
                style = FontStyle.Normal,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(weight.weight),
                    FontVariation.Setting("opsz", opticalSize),
                ),
            )
        },
    )
}
