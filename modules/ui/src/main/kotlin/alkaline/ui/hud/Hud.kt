package alkaline.ui.hud

import alkaline.client.modules.HudAnchor
import alkaline.client.modules.HudPlacement
import alkaline.client.modules.Module
import alkaline.client.modules.Modules
import alkaline.config.ConfigSnapshot
import alkaline.config.ConfigStore
import alkaline.renderer.vulkan.ui.glass
import alkaline.ui.theme.AlkalineIcons
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Live state of the HUD editor: the selected element, snapping, and where every element is on screen. */
class HudEditor {
    var selected by mutableStateOf<String?>(null)
    var snapping by mutableStateOf(true)
    /** Written during layout and read only while dragging, so it is plain (not Compose) state. */
    internal val bounds = java.util.concurrent.ConcurrentHashMap<String, Rect>()
    internal var guidesX by mutableStateOf<List<Float>>(emptyList())
    internal var guidesY by mutableStateOf<List<Float>>(emptyList())
}

/**
 * The in-game HUD: every enabled HUD module at its profile placement, plus vanilla's fixed overlays (titles, the
 * action bar, the player list). With an [editor] the elements can be dragged (snapping to the screen's edges and
 * center and to each other), scaled with the wheel, and the placement is written to the active profile on drop.
 */
@Composable
fun GameHud(frame: HudFrame, profile: ConfigStore, editor: HudEditor?, onDone: () -> Unit = {}) {
    val snapshot by alkaline.ui.menu.rememberConfig(profile)
    Box(Modifier.fillMaxSize()) {
        if (editor == null) {
            FixedOverlays(frame)
        }
        for (module in Modules.all()) {
            if (module.kind() != Module.Kind.HUD || !snapshot.get(module.enabled())) continue
            PlacedElement(module, snapshot, profile, frame, editor)
        }
        if (editor != null) {
            Guides(editor)
            EditorToolbar(editor, profile, snapshot, onDone, Modifier.align(Alignment.TopCenter).padding(top = 20.dp))
        }
    }
}

/** Screen position of an element's top left for an anchor and offset, in pixels. */
private fun anchored(anchor: HudAnchor, x: Float, y: Float, width: Float, height: Float, screenWidth: Int,
                     screenHeight: Int): Offset = Offset(
    anchor.horizontal() * (screenWidth - width) + x,
    anchor.vertical() * (screenHeight - height) + y,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlacedElement(module: Module, snapshot: ConfigSnapshot, store: ConfigStore, frame: HudFrame,
                          editor: HudEditor?) {
    val placement = module.placement()
    val density = LocalDensity.current.density
    val anchor = snapshot.get(placement.anchor())
    val scale = snapshot.get(placement.scale())
    val offsetX = snapshot.get(placement.x()) * density
    val offsetY = snapshot.get(placement.y()) * density
    // The element's laid-out size and screen while dragging; the live top left replaces the stored one.
    var drag by remember(module) { mutableStateOf<Offset?>(null) }
    val current = remember(module) { FloatArray(6) } // left, top, width, height, screen width, screen height
    val latestScale by rememberUpdatedState(scale)
    // A hidden element must not keep attracting snaps.
    androidx.compose.runtime.DisposableEffect(module, editor) {
        onDispose { editor?.bounds?.remove(module.id()) }
    }
    Box(Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints())
        val width = placeable.width * scale
        val height = placeable.height * scale
        val position = drag ?: anchored(anchor, offsetX, offsetY, width, height, constraints.maxWidth,
            constraints.maxHeight)
        current[0] = position.x
        current[1] = position.y
        current[2] = width
        current[3] = height
        current[4] = constraints.maxWidth.toFloat()
        current[5] = constraints.maxHeight.toFloat()
        editor?.bounds?.put(module.id(), Rect(position, Size(width, height)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.placeWithLayer(position.x.roundToInt(), position.y.roundToInt()) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }) {
        val editing = editor != null
        val selected = editor?.selected == module.id()
        val outline = MaterialTheme.colorScheme.primary
        Box(
            if (!editing) Modifier else Modifier
                .border(if (selected) 2.dp else 1.dp, outline.copy(alpha = if (selected) 1f else 0.45f),
                    RoundedCornerShape(10.dp))
                .pointerInput(module) {
                    detectDragGestures(
                        onDragStart = {
                            editor!!.selected = module.id()
                            drag = Offset(current[0], current[1])
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            val start = drag ?: Offset(current[0], current[1])
                            val moved = start + amount * latestScale
                            drag = if (editor!!.snapping) snap(editor, module.id(), moved, current, density) else {
                                editor.guidesX = emptyList()
                                editor.guidesY = emptyList()
                                moved
                            }
                        },
                        onDragEnd = {
                            drag?.let { commit(store, placement, it, current, density) }
                            drag = null
                            editor!!.guidesX = emptyList()
                            editor.guidesY = emptyList()
                        },
                        onDragCancel = { drag = null },
                    )
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (delta != 0f) {
                        editor!!.selected = module.id()
                        val next = (latestScale - delta * 0.05f).coerceIn(HudPlacement.MIN_SCALE, HudPlacement.MAX_SCALE)
                        store.trySet(placement.scale(), (next * 20f).roundToInt() / 20f)
                    }
                },
        ) {
            HudElement(module, frame, snapshot, editing)
        }
    }
}

/** Snaps a dragged element's edges and center to the screen's margins and center and to other elements. */
private fun snap(editor: HudEditor, id: String, moved: Offset, current: FloatArray, density: Float): Offset {
    val width = current[2]
    val height = current[3]
    val screenWidth = current[4]
    val screenHeight = current[5]
    val threshold = 6f * density
    val margin = 4f * density
    val xs = mutableListOf(margin, screenWidth / 2f, screenWidth - margin)
    val ys = mutableListOf(margin, screenHeight / 2f, screenHeight - margin)
    for ((other, rect) in editor.bounds) {
        if (other == id) continue
        xs += listOf(rect.left, rect.center.x, rect.right)
        ys += listOf(rect.top, rect.center.y, rect.bottom)
    }
    fun axis(start: Float, size: Float, candidates: List<Float>): Pair<Float, Float?> {
        var best: Float? = null
        var bestShift = threshold
        for (candidate in candidates) {
            for (edge in floatArrayOf(start, start + size / 2f, start + size)) {
                val shift = candidate - edge
                if (abs(shift) < abs(bestShift)) {
                    bestShift = shift
                    best = candidate
                }
            }
        }
        return if (best == null) start to null else start + bestShift to best
    }
    val (x, guideX) = axis(moved.x, width, xs)
    val (y, guideY) = axis(moved.y, height, ys)
    editor.guidesX = listOfNotNull(guideX)
    editor.guidesY = listOfNotNull(guideY)
    return Offset(x.coerceIn(0f, (screenWidth - width).coerceAtLeast(0f)), y.coerceIn(0f, (screenHeight - height).coerceAtLeast(0f)))
}

/** Writes a dropped element: the anchor of the screen third its center is in, and the offset from it in dp. */
private fun commit(store: ConfigStore, placement: HudPlacement, topLeft: Offset, current: FloatArray, density: Float) {
    val width = current[2]
    val height = current[3]
    val screenWidth = current[4]
    val screenHeight = current[5]
    val centerX = topLeft.x + width / 2f
    val centerY = topLeft.y + height / 2f
    val column = if (centerX < screenWidth / 3f) 0 else if (centerX < screenWidth * 2f / 3f) 1 else 2
    val row = if (centerY < screenHeight / 3f) 0 else if (centerY < screenHeight * 2f / 3f) 1 else 2
    val anchor = HudAnchor.of(column, row)
    val x = (topLeft.x - anchor.horizontal() * (screenWidth - width)) / density
    val y = (topLeft.y - anchor.vertical() * (screenHeight - height)) / density
    store.trySet(placement.anchor(), anchor)
    store.trySet(placement.x(), x.coerceIn(-HudPlacement.MAX_OFFSET, HudPlacement.MAX_OFFSET))
    store.trySet(placement.y(), y.coerceIn(-HudPlacement.MAX_OFFSET, HudPlacement.MAX_OFFSET))
}

internal fun <T> ConfigStore.trySet(setting: alkaline.config.Setting<T>, value: T) {
    runCatching { set(setting, value) }.onFailure { System.err.println("Rejected ${setting.id()}: ${it.message}") }
}

@Composable
private fun Guides(editor: HudEditor) {
    val color = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxSize()) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        for (x in editor.guidesX) {
            drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.5f, pathEffect = dash)
        }
        for (y in editor.guidesY) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), 1.5f, pathEffect = dash)
        }
    }
}

/** The editor's one frosted bar: the selected element's size and visibility, snapping, reset, and Done. */
@Composable
private fun EditorToolbar(editor: HudEditor, store: ConfigStore, snapshot: ConfigSnapshot, onDone: () -> Unit,
                          modifier: Modifier) {
    val selected = editor.selected?.let { Modules.find(it) }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.glass(28.dp),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected?.placement() != null) {
                val placement = selected.placement()
                val scale = snapshot.get(placement.scale())
                Text(selected.title(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.widthIn(min = 96.dp))
                FilledTonalIconButton(onClick = {
                    store.trySet(placement.scale(), (scale - 0.1f).coerceAtLeast(HudPlacement.MIN_SCALE))
                }, modifier = Modifier.size(36.dp)) { Text("−") }
                Text(String.format(Locale.ROOT, "%d%%", (scale * 100f).roundToInt()),
                    style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.width(48.dp))
                FilledTonalIconButton(onClick = {
                    store.trySet(placement.scale(), (scale + 0.1f).coerceAtMost(HudPlacement.MAX_SCALE))
                }, modifier = Modifier.size(36.dp)) { Text("+") }
                TextButton(onClick = {
                    alkaline.ui.menu.resetSetting(store, placement.anchor())
                    alkaline.ui.menu.resetSetting(store, placement.x())
                    alkaline.ui.menu.resetSetting(store, placement.y())
                    alkaline.ui.menu.resetSetting(store, placement.scale())
                }) { Text("Reset") }
                TextButton(onClick = {
                    store.trySet(selected.enabled(), false)
                    editor.selected = null
                }) { Text("Hide") }
            } else {
                Text("Drag elements to move them. Scroll to resize.", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Snap", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Switch(checked = editor.snapping, onCheckedChange = { editor.snapping = it })
            }
            Button(onClick = onDone) {
                Icon(AlkalineIcons.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Done")
            }
        }
    }
}

/** Vanilla's fixed overlays: the title and subtitle, the action bar above the hotbar, and the held player list. */
@Composable
private fun FixedOverlays(frame: HudFrame) {
    val white = androidx.compose.ui.graphics.Color.White
    val shadow = androidx.compose.ui.graphics.Shadow(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f),
        Offset(2f, 2f), 3f)
    Box(Modifier.fillMaxSize()) {
        if (frame.titleAlpha > 0f && (frame.title.isNotEmpty() || frame.subtitle.isNotEmpty())) {
            Column(Modifier.align(Alignment.Center).offset(y = (-40).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatted(frame.title, white), color = white.copy(alpha = frame.titleAlpha),
                    style = MaterialTheme.typography.displayMedium.copy(shadow = shadow), textAlign = TextAlign.Center)
                if (frame.subtitle.isNotEmpty()) {
                    Text(formatted(frame.subtitle, white), color = white.copy(alpha = frame.titleAlpha),
                        style = MaterialTheme.typography.titleLarge.copy(shadow = shadow), textAlign = TextAlign.Center)
                }
            }
        }
        if (frame.actionBarAlpha > 0f && frame.actionBar.isNotEmpty()) {
            Text(formatted(frame.actionBar, white), color = white.copy(alpha = frame.actionBarAlpha),
                style = MaterialTheme.typography.bodyLarge.copy(shadow = shadow),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Px * 68))
        }
        if (frame.showPlayers && frame.players.isNotEmpty()) {
            PlayerListOverlay(frame, Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
        }
    }
}

/**
 * GuiPlayerTabOverlay's content in Alkaline's card: the server's header, names in up to four columns of twenty
 * with latency, and its footer.
 */
@Composable
private fun PlayerListOverlay(frame: HudFrame, modifier: Modifier) {
    val base = MaterialTheme.colorScheme.onSurface
    val columns = frame.players.take(80).chunked(20)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
        modifier = modifier.glass(16.dp),
    ) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (frame.playerListHeader.isNotBlank()) {
            Text(formatted(frame.playerListHeader, base), style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            for (column in columns) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (player in column) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatted(player.name, base), style = MaterialTheme.typography.bodyMedium,
                                color = if (player.spectator) base.copy(alpha = 0.55f) else base,
                                modifier = Modifier.widthIn(min = 120.dp))
                            Text("${player.ping} ms", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (frame.playerListFooter.isNotBlank()) {
            Text(formatted(frame.playerListFooter, base), style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center)
        }
        }
    }
}
