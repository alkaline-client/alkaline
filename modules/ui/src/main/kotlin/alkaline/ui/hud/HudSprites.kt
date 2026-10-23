package alkaline.ui.hud

import alkaline.renderer.asset.AssetSource
import alkaline.renderer.image.ImageDecoder
import alkaline.renderer.image.RgbaImage
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * Vanilla textures the HUD draws (icons.png for hearts and hunger, item sprites for the hotbar, inventory.png for
 * effect icons), decoded from the game's assets on first use and kept. Animated sprites (vertical strips) show
 * their first frame. Without assets every lookup is null and elements fall back to text.
 */
class HudSprites(private val assets: AssetSource?) {
    private val cache = ConcurrentHashMap<String, Entry>()

    private class Entry(val bitmap: ImageBitmap?)

    /**
     * A texture by path under {@code textures/} without {@code .png}, for example {@code gui/icons}, or an image
     * the game rendered and [put] under a {@code gen:} key (item icons).
     */
    fun texture(path: String): ImageBitmap? =
        if (path.startsWith(GENERATED)) cache[path]?.bitmap else cache.computeIfAbsent(path) { Entry(load(it)) }.bitmap

    /** Keeps an image the game rendered (any thread), under a key starting with {@code gen:}. */
    fun put(key: String, image: RgbaImage) {
        require(key.startsWith(GENERATED)) { "Generated keys start with $GENERATED" }
        cache[key] = Entry(bitmap(image, image.height()))
    }

    private fun bitmap(image: RgbaImage, height: Int): ImageBitmap {
        val pixels = image.copyPixels().copyOf(image.width() * height * 4)
        val info = ImageInfo(image.width(), height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        return org.jetbrains.skia.Image.makeRaster(info, pixels, image.width() * 4).toComposeImageBitmap()
    }

    private fun load(path: String): ImageBitmap? {
        val source = assets ?: return null
        return runCatching {
            source.open("assets/minecraft/textures/$path.png")?.use { input ->
                val image = ImageDecoder.decode(input)
                bitmap(image, if (image.height() > image.width() && path.startsWith("items/")) image.width() else image.height())
            }
        }.getOrNull()
    }

    private companion object {
        const val GENERATED = "gen:"
    }
}

/** The sprites of the game the HUD is drawn over; null in menus and previews without assets. */
val LocalHudSprites = staticCompositionLocalOf<HudSprites?> { null }
