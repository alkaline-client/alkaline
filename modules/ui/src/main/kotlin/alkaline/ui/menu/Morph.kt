package alkaline.ui.menu

import alkaline.renderer.vulkan.ui.LocalGlassFade
import alkaline.renderer.vulkan.ui.glass
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

/** How long a page takes to fade in (after a short delay) and out around a page change. */
internal const val PAGE_FADE_IN_MILLIS = 220
internal const val PAGE_FADE_IN_DELAY_MILLIS = 90
internal const val PAGE_FADE_OUT_MILLIS = 120

/** How long the arriving panel's content (or action's label) takes to appear once the container has landed. */
private const val REVEAL_MILLIS = 180

/** The pages a title action grows into. */
private val Morphing = setOf(MenuPage.SINGLEPLAYER, MenuPage.MULTIPLAYER, MenuPage.SETTINGS)

/**
 * Material's container transform between a title action and the page it opens: the action's button grows into
 * the page's panel, and the panel shrinks back into it.
 *
 * One container draws the whole flight: a rounded rectangle (and its GPU glass) whose bounds, color and corners
 * follow a spring, placed in the layout phase so the pages do not recompose per frame. The real action and panel
 * hide while it flies, and what they draw on their own fades in once it has landed. The interface is rasterized
 * on the CPU; Compose's shared-element transition (a lookahead pass over both pages and an overlay redraw of both
 * elements every frame) made each frame of the morph two to three times as slow as this.
 */
internal class MorphState {
    /** Where each action and each page's panel last laid out, in root pixels; written during layout. */
    internal val sources = HashMap<MenuPage, Rect>()
    internal val targets = HashMap<MenuPage, Rect>()
    /** Each action's container color as last composed (its hover color when it was clicked). */
    internal val sourceColors = HashMap<MenuPage, Color>()

    /** The page whose container is flying, if any. */
    var page by mutableStateOf<MenuPage?>(null)
        private set
    /** The page whose arriving element is fading in what it draws after a flight. */
    var revealing by mutableStateOf<MenuPage?>(null)
        private set

    internal var from = Rect.Zero
    internal var to = Rect.Zero
    internal var fromColor = Color.Transparent
    internal var toColor = Color.Transparent
    internal var fromRadius = 0f
    internal var toRadius = 0f
    internal val progress = Animatable(0f)
    internal val reveal = Animatable(1f)

    /** Whether [page]'s action and panel give way to the flying container. */
    fun flying(page: MenuPage): Boolean = this.page == page

    /** Opacity of what [page]'s action or panel draws on its own: hidden in flight, fading in after landing. */
    fun contentAlpha(page: MenuPage): Float = when {
        this.page == page -> 0f
        revealing == page -> reveal.value
        else -> 1f
    }

    internal suspend fun run(
        previous: MenuPage,
        next: MenuPage,
        panelColor: Color,
        buttonRadius: Float,
        panelRadius: Float,
        spatial: FiniteAnimationSpec<Float>,
    ) {
        val opens = previous == MenuPage.TITLE && next in Morphing
        val closes = next == MenuPage.TITLE && previous in Morphing
        page = null
        revealing = null
        if (!opens && !closes) return
        val morphed = if (opens) next else previous
        val start = (if (opens) sources[morphed] else targets[morphed]) ?: return
        // The arriving element reports its bounds on its first layout; a stale entry would aim at an old place.
        val arrivals = if (opens) targets else sources
        arrivals.remove(morphed)
        from = start
        to = start
        fromColor = if (opens) sourceColors[morphed] ?: panelColor else panelColor
        toColor = if (opens) panelColor else sourceColors[morphed] ?: panelColor
        fromRadius = if (opens) buttonRadius else panelRadius
        toRadius = if (opens) panelRadius else buttonRadius
        progress.snapTo(0f)
        reveal.snapTo(0f)
        page = morphed
        var frames = 0
        while (arrivals[morphed] == null && frames++ < 30) withFrameNanos { }
        to = arrivals[morphed] ?: start
        if (!opens) toColor = sourceColors[morphed] ?: toColor
        try {
            progress.animateTo(1f, spatial)
        } finally {
            page = null
        }
        revealing = morphed
        reveal.animateTo(1f, tween(REVEAL_MILLIS))
        revealing = null
    }
}

internal val LocalMorph = staticCompositionLocalOf<MorphState?> { null }

/** Starts a flight whenever the page changes between the title and a page it opens. */
@Composable
internal fun MorphDriver(state: MorphState, page: MenuPage, buttonHeight: Dp) {
    val panelColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = backdropAlpha(0.66f))
    val spatial: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val density = LocalDensity.current
    val shown = remember { arrayOf(page) }
    LaunchedEffect(page) {
        val previous = shown[0]
        shown[0] = page
        if (previous != page) {
            with(density) {
                state.run(previous, page, panelColor, (buttonHeight / 2).toPx(), PanelRadius.toPx(), spatial)
            }
        }
    }
}

/** The flying container; composed below the pages, so the landing panel's content fades in over it. */
@Composable
internal fun MorphContainer(state: MorphState) {
    if (state.page == null) return
    // The corners follow the flight; reading the progress here recomposes only this small box.
    val radius = lerp(state.fromRadius, state.toRadius, state.progress.value.coerceIn(0f, 1f))
    val radiusDp = with(LocalDensity.current) { radius.toDp() }
    Box(
        Modifier
            .layout { measurable, constraints ->
                val bounds = lerp(state.from, state.to, state.progress.value)
                val placeable = measurable.measure(Constraints.fixed(
                    bounds.width.roundToInt().coerceAtLeast(0), bounds.height.roundToInt().coerceAtLeast(0)))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(bounds.left.roundToInt(), bounds.top.roundToInt())
                }
            }
            .glass(radiusDp, fades = false)
            .drawBehind {
                drawRoundRect(lerp(state.fromColor, state.toColor, state.progress.value.coerceIn(0f, 1f)),
                    cornerRadius = CornerRadius(radius))
            },
    )
}

/** Reports this action's bounds and [color] for flights into [page]. */
internal fun Modifier.morphSource(state: MorphState?, page: MenuPage, color: Color): Modifier {
    if (state == null) return this
    state.sourceColors[page] = color
    return onGloballyPositioned { state.sources[page] = it.boundsInRoot() }
}

/** Reports this panel's bounds for flights into [page]. */
internal fun Modifier.morphTarget(state: MorphState?, page: MenuPage): Modifier =
    if (state == null) this else onGloballyPositioned { state.targets[page] = it.boundsInRoot() }

/** Hides what the container stands in for while it flies, and fades it in once it has landed. */
internal fun Modifier.morphContent(state: MorphState?, page: MenuPage): Modifier =
    if (state == null) this else graphicsLayer {
        alpha = state.contentAlpha(page)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }

/**
 * Fades one menu page in or out, frosted surfaces included (Compose alpha does not reach the GPU backdrop). The
 * fade multiplies each draw's alpha ([CompositingStrategy.ModulateAlpha]) rather than drawing the page into a
 * window-sized offscreen layer and blending that, which is costly when the interface is rasterized on the CPU.
 */
@Composable
internal fun MorphPage(visibility: AnimatedVisibilityScope, content: @Composable () -> Unit) {
    val fade = visibility.fade(PAGE_FADE_IN_MILLIS, PAGE_FADE_IN_DELAY_MILLIS, PAGE_FADE_OUT_MILLIS)
    CompositionLocalProvider(LocalGlassFade provides fade) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = fade.value
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }) { content() }
    }
}

/** This visibility's opacity: [inMillis] after [inDelayMillis] on the way in, [outMillis] on the way out. */
@Composable
private fun AnimatedVisibilityScope.fade(inMillis: Int, inDelayMillis: Int, outMillis: Int): State<Float> =
    transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) tween(inMillis, inDelayMillis) else tween(outMillis)
        },
        label = "fade",
    ) { if (it == EnterExitState.Visible) 1f else 0f }

/**
 * Fades the frosted surfaces inside this animated visibility with the same timing as its fadeIn/fadeOut, so the
 * GPU backdrop never shows without the content above it.
 */
@Composable
internal fun AnimatedVisibilityScope.FadingGlass(
    inMillis: Int,
    inDelayMillis: Int,
    outMillis: Int,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalGlassFade provides fade(inMillis, inDelayMillis, outMillis), content = content)
}
