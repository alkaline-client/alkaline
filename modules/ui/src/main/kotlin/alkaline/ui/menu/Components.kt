package alkaline.ui.menu

import alkaline.renderer.vulkan.ui.glass
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * The Frosted surfaces setting. Without the blur, a surface lying straight on the menu background has nothing
 * behind it but the raw scene, so it carries its own legibility: see [backdropAlpha].
 */
internal val LocalFrosted = staticCompositionLocalOf { true }

/** [alpha] for a surface on the menu background: translucent over frosted glass, nearly solid without it. */
@Composable
internal fun backdropAlpha(alpha: Float): Float = if (LocalFrosted.current) alpha else maxOf(alpha, 0.94f)

/**
 * Container and content colors for a secondary control: calm at rest, the primary container while hovered, so
 * the control under the pointer reads as the one a click will act on. The change is animated briefly; it
 * explains state, it does not decorate.
 */
@Composable
internal fun hoverColors(hovered: Boolean, selected: Boolean = false): Pair<State<Color>, State<Color>> {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        hovered -> scheme.primaryContainer.copy(alpha = 0.92f)
        selected -> scheme.secondaryContainer.copy(alpha = 0.9f)
        else -> scheme.surfaceContainerHigh.copy(alpha = backdropAlpha(0.62f))
    }
    val content = when {
        hovered -> scheme.onPrimaryContainer
        selected -> scheme.onSecondaryContainer
        else -> scheme.onSurface
    }
    return animateColorAsState(container, tween(140), label = "hover-container") to
        animateColorAsState(content, tween(140), label = "hover-content")
}

/**
 * Tonal button over frosted glass that highlights while hovered. With a [morph] page it is where that page's
 * panel grows from and shrinks back into (see [MorphState]): its pill is a rounded rectangle with the same corners
 * and no pressed-shape animation, and while the morph container flies it gives way to it, then fades its label in.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HoverAction(
    icon: ImageVector?,
    label: String,
    height: Dp,
    modifier: Modifier = Modifier.fillMaxWidth(),
    morph: MenuPage? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val (container, content) = hoverColors(hovered)
    val state = if (morph != null) LocalMorph.current else null
    val flying = morph != null && state?.flying(morph) == true
    FilledTonalButton(
        onClick = onClick,
        shapes = if (morph == null) ButtonDefaults.shapesFor(height)
            else RoundedCornerShape(height / 2).let { ButtonShapes(it, it) },
        contentPadding = ButtonDefaults.contentPaddingFor(height),
        interactionSource = interaction,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = container.value, contentColor = content.value),
        modifier = (if (morph == null || state == null) modifier else modifier.morphSource(state, morph, container.value)
            // While the container flies the whole button gives way, its state layer included.
            .graphicsLayer { alpha = if (state.flying(morph)) 0f else 1f })
            .height(height).glass(height / 2, enabled = !flying),
    ) {
        Row(
            if (morph == null) Modifier else Modifier.morphContent(state, morph),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
                Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
            }
            Text(label, style = ButtonDefaults.textStyleFor(height))
        }
    }
}

/** Builds a bitmap from straight-alpha ARGB pixels (world icons, player faces). */
internal fun argbBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val bytes = ByteArray(width * height * 4)
    for (index in 0 until width * height) {
        val argb = pixels[index]
        bytes[index * 4] = (argb and 0xFF).toByte()
        bytes[index * 4 + 1] = (argb shr 8 and 0xFF).toByte()
        bytes[index * 4 + 2] = (argb shr 16 and 0xFF).toByte()
        bytes[index * 4 + 3] = (argb ushr 24).toByte()
    }
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    return org.jetbrains.skia.Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
}

/** Decodes PNG bytes (server favicons); null when they are not a readable image. */
internal fun pngBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/** Pixel art scaled with hard edges, clipped to [shape]. */
@Composable
internal fun PixelImage(bitmap: ImageBitmap, size: Dp, shape: Shape, modifier: Modifier = Modifier) {
    Image(
        bitmap, null, modifier.size(size).clip(shape),
        filterQuality = FilterQuality.None,
    )
}

/**
 * A single-line pill text field: a leading icon, the value, and a trailing slot. Used for the world search
 * and the multiplayer address bar.
 */
@Composable
internal fun PillField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
        modifier = modifier.height(52.dp),
    ) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            trailing()
        }
    }
}

/** "just now", "5 minutes ago", "yesterday", "12 days ago", or a date for older times. */
internal fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0L) return "Never played"
    val minutes = (now - millis) / 60_000L
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
        minutes < 60 * 24 -> (minutes / 60).let { "$it hour${if (it == 1L) "" else "s"} ago" }
        minutes < 60 * 48 -> "Yesterday"
        minutes < 60 * 24 * 60 -> "${minutes / (60 * 24)} days ago"
        else -> java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
            .format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))
    }
}
