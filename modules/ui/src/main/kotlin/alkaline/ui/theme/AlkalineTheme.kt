package alkaline.ui.theme

import alkaline.client.settings.ClientSettings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeExpressive
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Alkaline's color themes. Each [ClientSettings.Theme] maps to a Material You ("Monet") dynamic scheme from
 * Google's material-color-utilities, generated from one seed color, Alkaline's mineral teal: the Expressive
 * variant (primary hue rotated away from the seed, secondary and tertiary on further hues) under the 2025 color
 * spec that Material 3 Expressive introduced.
 *
 * Schemes are generated once per theme and cached; the generator is pure CPU math with no platform dependency.
 */
object AlkalineThemes {
    /** Alkaline's seed color, sRGB. */
    const val SEED: Int = 0xFF1F8A80.toInt()

    private val cache = java.util.concurrent.ConcurrentHashMap<ClientSettings.Theme, ColorScheme>()

    fun colorScheme(theme: ClientSettings.Theme): ColorScheme = cache.computeIfAbsent(theme) { monet(SEED, it.dark()) }

    /**
     * The few theme colors the renderer needs, as sRGB ARGB ints, for Java callers: the surface behind the
     * loading screen and menus, and the strong foreground used as ink by backgrounds that draw with the theme.
     */
    @JvmStatic
    fun palette(theme: ClientSettings.Theme): ThemePalette {
        val scheme = colorScheme(theme)
        return ThemePalette(
            dark = theme.dark(),
            surface = scheme.surface.toArgb(),
            onSurface = scheme.onSurface.toArgb(),
            primary = scheme.primary.toArgb(),
        )
    }

    private fun monet(seed: Int, dark: Boolean): ColorScheme {
        val scheme = SchemeExpressive(Hct.fromInt(seed), dark, 0.0, ColorSpec.SpecVersion.SPEC_2025, DynamicScheme.Platform.PHONE)
        val roles = MaterialDynamicColors()
        fun role(color: DynamicColor) = Color(color.getArgb(scheme))
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = role(roles.primary()),
            onPrimary = role(roles.onPrimary()),
            primaryContainer = role(roles.primaryContainer()),
            onPrimaryContainer = role(roles.onPrimaryContainer()),
            inversePrimary = role(roles.inversePrimary()),
            secondary = role(roles.secondary()),
            onSecondary = role(roles.onSecondary()),
            secondaryContainer = role(roles.secondaryContainer()),
            onSecondaryContainer = role(roles.onSecondaryContainer()),
            tertiary = role(roles.tertiary()),
            onTertiary = role(roles.onTertiary()),
            tertiaryContainer = role(roles.tertiaryContainer()),
            onTertiaryContainer = role(roles.onTertiaryContainer()),
            background = role(roles.background()),
            onBackground = role(roles.onBackground()),
            surface = role(roles.surface()),
            onSurface = role(roles.onSurface()),
            surfaceVariant = role(roles.surfaceVariant()),
            onSurfaceVariant = role(roles.onSurfaceVariant()),
            surfaceTint = role(roles.surfaceTint()),
            inverseSurface = role(roles.inverseSurface()),
            inverseOnSurface = role(roles.inverseOnSurface()),
            error = role(roles.error()),
            onError = role(roles.onError()),
            errorContainer = role(roles.errorContainer()),
            onErrorContainer = role(roles.onErrorContainer()),
            outline = role(roles.outline()),
            outlineVariant = role(roles.outlineVariant()),
            scrim = role(roles.scrim()),
            surfaceBright = role(roles.surfaceBright()),
            surfaceDim = role(roles.surfaceDim()),
            surfaceContainer = role(roles.surfaceContainer()),
            surfaceContainerHigh = role(roles.surfaceContainerHigh()),
            surfaceContainerHighest = role(roles.surfaceContainerHighest()),
            surfaceContainerLow = role(roles.surfaceContainerLow()),
            surfaceContainerLowest = role(roles.surfaceContainerLowest()),
        )
    }
}

/** Theme colors for renderer code, sRGB ARGB. */
data class ThemePalette(val dark: Boolean, val surface: Int, val onSurface: Int, val primary: Int)

private val AlkalineShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private fun alkalineTypography(): Typography {
    val base = Typography()
    val text = AlkalineFonts.text
    val title = AlkalineFonts.title
    val display = AlkalineFonts.display
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = display),
        displayMedium = base.displayMedium.copy(fontFamily = display),
        displaySmall = base.displaySmall.copy(fontFamily = display, fontWeight = FontWeight.Black, letterSpacing = 6.sp),
        headlineLarge = base.headlineLarge.copy(fontFamily = title, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = title, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = title, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = title, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = text, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = text, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = text),
        bodyMedium = base.bodyMedium.copy(fontFamily = text),
        bodySmall = base.bodySmall.copy(fontFamily = text),
        labelLarge = base.labelLarge.copy(fontFamily = text, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelMedium = base.labelMedium.copy(fontFamily = text, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontFamily = text, fontWeight = FontWeight.Medium),
    )
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlkalineTheme(theme: ClientSettings.Theme = ClientSettings.Theme.MONET_DARK, content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = AlkalineThemes.colorScheme(theme),
        motionScheme = MotionScheme.expressive(),
        shapes = AlkalineShapes,
        typography = alkalineTypography(),
        content = content,
    )
}
