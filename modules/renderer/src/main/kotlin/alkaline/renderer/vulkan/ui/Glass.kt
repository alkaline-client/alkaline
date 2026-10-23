package alkaline.renderer.vulkan.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import java.util.concurrent.ConcurrentHashMap

/**
 * A rounded rectangle, in UI pixels, under which the renderer draws a blurred copy of the menu background.
 * Compose cannot blur what lies behind it (the Vulkan scene is not part of its canvas), so surfaces declare
 * their shape with [glass] and the GPU composites a frosted backdrop beneath the UI layer.
 */
data class GlassRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cornerRadius: Float,
    /** Straight-alpha ARGB tint mixed over the blurred backdrop. */
    val tint: Int,
    /** How much of the backdrop shows, 0..1, for surfaces that fade (chat lines). */
    val opacity: Float = 1f,
    /** HUD elements decide per element whether they blur; menu surfaces follow the Frosted surfaces setting. */
    val hud: Boolean = false,
)

/** A named rectangle, in UI pixels, where the renderer should place 3D content under the UI layer. */
data class SceneAnchor(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal class GlassRegistry {
    private val regions = ConcurrentHashMap<Any, GlassRegion>()
    private val anchors = ConcurrentHashMap<String, SceneAnchor>()

    fun update(key: Any, region: GlassRegion?) {
        if (region == null) regions.remove(key) else regions[key] = region
    }

    fun anchor(name: String, anchor: SceneAnchor?) {
        if (anchor == null) anchors.remove(name) else anchors[name] = anchor
    }

    fun snapshot(): List<GlassRegion> = regions.values.toList()

    fun anchorSnapshot(): Map<String, SceneAnchor> = HashMap(anchors)
}

internal val LocalGlassRegistry = staticCompositionLocalOf<GlassRegistry?> { null }

/**
 * Requests a frosted backdrop with the element's bounds and corner radius. No-op outside the in-game host and
 * when not [enabled]. [hud] marks HUD elements, which blur by their own option rather than the menu setting.
 */
fun Modifier.glass(
    cornerRadius: Dp,
    tint: Int = 0x00000000,
    enabled: Boolean = true,
    hud: Boolean = false,
    opacity: Float = 1f,
): Modifier = composed {
    val registry = LocalGlassRegistry.current
    if (registry == null || !enabled) return@composed this
    val key = remember { Any() }
    val bounds = remember { arrayOfNulls<Rect>(1) }
    val radius = with(LocalDensity.current) { cornerRadius.toPx() }
    fun publish() {
        val rect = bounds[0]
        registry.update(
            key,
            if (rect != null && rect.width > 0f && rect.height > 0f && opacity > 0f) {
                GlassRegion(rect.left, rect.top, rect.right, rect.bottom, radius, tint, opacity.coerceAtMost(1f), hud)
            } else {
                null
            },
        )
    }
    DisposableEffect(registry, key) {
        onDispose { registry.update(key, null) }
    }
    // Tint and opacity can change without the element moving, which onGloballyPositioned would not report.
    SideEffect { publish() }
    onGloballyPositioned { coordinates ->
        bounds[0] = coordinates.boundsInRoot()
        publish()
    }
}

/** Publishes this element's bounds as a named [SceneAnchor] for renderer-drawn content. */
fun Modifier.sceneAnchor(name: String): Modifier = composed {
    val registry = LocalGlassRegistry.current ?: return@composed this
    DisposableEffect(registry, name) {
        onDispose { registry.anchor(name, null) }
    }
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInRoot()
        registry.anchor(name, SceneAnchor(bounds.left, bounds.top, bounds.right, bounds.bottom))
    }
}
