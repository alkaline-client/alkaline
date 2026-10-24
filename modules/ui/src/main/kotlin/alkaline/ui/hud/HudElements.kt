package alkaline.ui.hud

import alkaline.client.modules.Module
import alkaline.client.modules.Modules
import alkaline.config.ConfigSnapshot
import alkaline.renderer.vulkan.ui.glass
import alkaline.renderer.vulkan.ui.sceneAnchor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.floor
import kotlin.math.roundToInt

/** One vanilla GUI pixel at element scale 1 (vanilla's Normal GUI scale where Auto picks 100%). */
internal val Px: Dp = 2.dp

private val ChipShape = RoundedCornerShape(10.dp)
private val TextShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(1f, 1f), 2f)

/** Whether the element being drawn frosts the scene behind its surfaces (its module's Blur option). */
private val LocalHudBlur = staticCompositionLocalOf { false }

/** The frosted backdrop of a HUD surface, when its module's Blur option is on. */
@Composable
private fun Modifier.hudGlass(radius: Dp, opacity: Float = 1f): Modifier =
    glass(radius, enabled = LocalHudBlur.current, hud = true, opacity = opacity)

/** Draws one HUD module's element; [preview] shows sample values where the game has none (the HUD editor). */
@Composable
internal fun HudElement(module: Module, frame: HudFrame, options: ConfigSnapshot, preview: Boolean) {
    val blur = Modules.blur(module)?.let { options.get(it) } ?: false
    CompositionLocalProvider(LocalHudBlur provides blur) {
        ModuleElement(module, frame, options, preview)
    }
}

@Composable
private fun ModuleElement(module: Module, frame: HudFrame, options: ConfigSnapshot, preview: Boolean) {
    when (module) {
        Modules.FPS -> Chip("${frame.fps} FPS")
        Modules.COORDINATES -> {
            val text = "X ${floor(frame.x).toInt()}  Y ${floor(frame.y).toInt()}  Z ${floor(frame.z).toInt()}"
            Chip(if (options.get(Modules.COORDINATES_FACING)) "$text  ${facing(frame.yaw)}" else text)
        }
        Modules.CPS -> Chip(if (options.get(Modules.CPS_RIGHT)) "${frame.leftCps} | ${frame.rightCps} CPS" else "${frame.leftCps} CPS")
        Modules.SPEED -> Chip(String.format(Locale.ROOT, "%.1f blocks/s", frame.speed))
        Modules.KEYSTROKES -> Keystrokes(frame, options)
        Modules.PING -> Chip(if (frame.ping < 0) "— ms" else "${frame.ping} ms")
        Modules.CLOCK -> Chip(LocalTime.now().format(DateTimeFormatter.ofPattern(
            if (options.get(Modules.CLOCK_24_HOUR)) "HH:mm" else "h:mm a", Locale.ROOT)))
        Modules.SERVER -> Chip(frame.server.ifEmpty { if (preview) "play.example.net" else "Singleplayer" })
        Modules.POTIONS -> Potions(frame, preview)
        Modules.SCOREBOARD -> Scoreboard(frame, options, preview)
        Modules.CHAT -> Chat(frame, options, preview)
        Modules.STATUS -> Status(frame, preview)
        Modules.HOTBAR -> Hotbar(frame)
        Modules.TOGGLE_SPRINT -> {
            val status = frame.movementStatus.ifEmpty { if (preview) HudFrame.SAMPLE.movementStatus else "" }
            if (options.get(Modules.TOGGLE_SPRINT_READOUT) && status.isNotEmpty()) Chip(status)
        }
        else -> {}
    }
}

/** EntityPlayer's eight-way facing from Minecraft's yaw (0 south, 90 west). */
internal fun facing(yaw: Float): String {
    val names = arrayOf("S", "SW", "W", "NW", "N", "NE", "E", "SE")
    return names[Math.floorMod((yaw / 45f).roundToInt(), 8)]
}

/** A frosted pill with one line: the shape every readout module shares. */
@Composable
internal fun Chip(text: String) {
    Surface(
        shape = ChipShape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.62f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.hudGlass(10.dp),
    ) {
        Text(
            text, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Keystrokes(frame: HudFrame, options: ConfigSnapshot) {
    val labels = frame.keyLabels
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Key(labels.getOrElse(0) { "W" }, frame.keys and HudFrame.KEY_FORWARD != 0, 40.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Key(labels.getOrElse(1) { "A" }, frame.keys and HudFrame.KEY_LEFT != 0, 40.dp)
            Key(labels.getOrElse(2) { "S" }, frame.keys and HudFrame.KEY_BACK != 0, 40.dp)
            Key(labels.getOrElse(3) { "D" }, frame.keys and HudFrame.KEY_RIGHT != 0, 40.dp)
        }
        if (options.get(Modules.KEYSTROKES_MOUSE)) {
            val cps = options.get(Modules.KEYSTROKES_CPS)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Key("LMB", frame.keys and HudFrame.KEY_ATTACK != 0, 62.dp, if (cps) "${frame.leftCps} CPS" else null)
                Key("RMB", frame.keys and HudFrame.KEY_USE != 0, 62.dp, if (cps) "${frame.rightCps} CPS" else null)
            }
        }
        if (options.get(Modules.KEYSTROKES_SPACE)) {
            Key("—", frame.keys and HudFrame.KEY_JUMP != 0, 128.dp, height = 22.dp)
        }
    }
}

/** A key cap: calm at rest, the primary container while held. */
@Composable
private fun Key(label: String, held: Boolean, width: Dp, detail: String? = null, height: Dp = 40.dp) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (held) scheme.primaryContainer else scheme.surfaceContainer.copy(alpha = 0.62f),
        contentColor = if (held) scheme.onPrimaryContainer else scheme.onSurface,
        modifier = Modifier.size(width, height).hudGlass(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label.uppercase(Locale.ROOT).take(6), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

/** Potion.getDurationString: m:ss, or **:** for effects that last (practically) forever. */
internal fun duration(ticks: Int): String {
    if (ticks > 32147) return "**:**"
    val seconds = ticks / 20
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

private val Roman = arrayOf("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

@Composable
private fun Potions(frame: HudFrame, preview: Boolean) {
    val effects = frame.effects.ifEmpty { if (preview) HudFrame.SAMPLE.effects else emptyList() }
    if (effects.isEmpty()) return
    val inventory = LocalHudSprites.current?.texture("gui/container/inventory")
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.62f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.hudGlass(14.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (effect in effects) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (inventory != null && effect.iconIndex >= 0) {
                        Canvas(Modifier.size(18.dp * 1.5f)) {
                            sprite(inventory, (effect.iconIndex % 8) * 18, 198 + (effect.iconIndex / 8) * 18, 18, 18,
                                Offset.Zero, size.width / 18f)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Column {
                        val level = Roman.getOrElse(effect.level) { effect.level.toString() }
                        Text(if (effect.level > 1) "${effect.name} $level" else effect.name,
                            style = MaterialTheme.typography.labelLarge)
                        Text(duration(effect.ticks), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Scoreboard(frame: HudFrame, options: ConfigSnapshot, preview: Boolean) {
    val title = frame.sidebarTitle ?: if (preview) HudFrame.SAMPLE.sidebarTitle else null
    title ?: return
    val lines = if (frame.sidebarTitle != null) frame.sidebar else HudFrame.SAMPLE.sidebar
    val numbers = options.get(Modules.SCOREBOARD_NUMBERS)
    val base = MaterialTheme.colorScheme.onSurface
    val style = MaterialTheme.typography.bodySmall.copy(shadow = TextShadow)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f),
        modifier = Modifier.widthIn(min = 120.dp).hudGlass(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(formatted(title, base), style = MaterialTheme.typography.titleSmall.copy(shadow = TextShadow),
                modifier = Modifier.align(Alignment.CenterHorizontally), maxLines = 1)
            Spacer(Modifier.height(2.dp))
            for (line in lines) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatted(line.text, base), style = style, maxLines = 1)
                    if (numbers) {
                        Spacer(Modifier.width(12.dp))
                        Text(line.score.toString(), style = style, color = Color(0xFFFF5555), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chat(frame: HudFrame, options: ConfigSnapshot, preview: Boolean) {
    val lines = options.get(Modules.CHAT_LINES)
    val fadeTicks = options.get(Modules.CHAT_FADE_SECONDS) * 20L
    val visible = frame.chat.ifEmpty { if (preview) HudFrame.SAMPLE.chat else emptyList() }.take(lines)
        .filter { preview || frame.tick - it.ageTicks < fadeTicks }
    if (visible.isEmpty()) {
        if (preview) Spacer(Modifier.size(320.dp, 24.dp))
        return
    }
    val base = Color.White
    Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        for (line in visible.asReversed()) {
            // GuiNewChat fades a line over its last second.
            val age = if (preview) 0L else frame.tick - line.ageTicks
            val alpha = ((fadeTicks - age) / 20f).coerceIn(0f, 1f)
            Text(
                formatted(line.text, base),
                style = MaterialTheme.typography.bodyMedium.copy(shadow = TextShadow),
                color = base.copy(alpha = alpha),
                modifier = Modifier.hudGlass(6.dp, alpha).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.32f * alpha)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Vanilla's status bars (GuiIngame.renderPlayerStats) with icons.png: armor, hearts, hunger, air, experience. */
@Composable
private fun Status(frame: HudFrame, preview: Boolean) {
    val stats = if (preview && frame.health <= 0f) HudFrame.SAMPLE else frame
    val icons = LocalHudSprites.current?.texture("gui/icons")
    val creative = stats.gameMode == 1 || stats.gameMode == 3
    if (creative && !preview) return
    val scheme = MaterialTheme.colorScheme
    // GuiIngame.renderPlayerStats: rows of ten hearts for max health plus absorption, squeezed closer (down to 3
    // pixels apart) the more rows there are; the armor row sits above the top one.
    val rows = ceil((stats.maxHealth + stats.absorption) / 2f / 10f).toInt().coerceAtLeast(1)
    val spacing = max(10 - (rows - 2), 3)
    val extra = (rows - 1) * spacing
    Box(Modifier.size(Px * 182, Px * (26 + extra))) {
        if (icons != null) {
            Canvas(Modifier.size(Px * 182, Px * (26 + extra))) {
                val unit = size.width / 182f
                val hurt = stats.hurtTicks > 0 && stats.tick / 3 % 2 == 1L
                val health = ceil(stats.health).toInt().coerceAtLeast(0)
                val base = 10f + extra
                if (stats.armor > 0) {
                    for (index in 0 until 10) {
                        val u = when {
                            index * 2 + 1 < stats.armor -> 34
                            index * 2 + 1 == stats.armor -> 25
                            else -> 16
                        }
                        sprite(icons, u, 9, 9, 9, Offset(index * 8f * unit, (base - extra - 10f) * unit), unit)
                    }
                }
                val random = java.util.Random(stats.tick * 312871)
                val wave = if (stats.regeneration) (stats.tick % ceil(stats.maxHealth + 5f).toInt()).toInt() else -1
                val style = 16 + when (stats.heartEffect) { 1 -> 36; 2 -> 72; else -> 0 }
                var golden = stats.absorption
                for (index in ceil((stats.maxHealth + stats.absorption) / 2f).toInt() - 1 downTo 0) {
                    val row = ceil((index + 1) / 10f).toInt() - 1
                    var y = base - row * spacing
                    if (health <= 4) y += random.nextInt(2)
                    if (index == wave) y -= 2f
                    val position = Offset(index % 10 * 8f * unit, y * unit)
                    sprite(icons, if (hurt) 25 else 16, 0, 9, 9, position, unit)
                    if (golden > 0f) {
                        val half = golden == stats.absorption && stats.absorption % 2f == 1f
                        sprite(icons, style + if (half) 153 else 144, 0, 9, 9, position, unit)
                        golden -= 2f
                    } else {
                        when {
                            index * 2 + 1 < health -> sprite(icons, style + 36, 0, 9, 9, position, unit)
                            index * 2 + 1 == health -> sprite(icons, style + 45, 0, 9, 9, position, unit)
                        }
                    }
                }
                if (!stats.riding) {
                    val food = stats.food.coerceIn(0, 20)
                    val foodStyle = if (stats.hungerEffect) 52 else 16
                    for (index in 0 until 10) {
                        var y = base
                        if (stats.saturation <= 0f && stats.tick % (food * 3 + 1) == 0L) y += random.nextInt(3) - 1
                        val position = Offset((182f - 9f - index * 8f) * unit, y * unit)
                        sprite(icons, if (stats.hungerEffect) 133 else 16, 27, 9, 9, position, unit)
                        when {
                            index * 2 + 1 < food -> sprite(icons, foodStyle + 36, 27, 9, 9, position, unit)
                            index * 2 + 1 == food -> sprite(icons, foodStyle + 45, 27, 9, 9, position, unit)
                        }
                    }
                }
                if (stats.air < 300) {
                    val full = ceil((stats.air - 2) * 10.0 / 300.0).toInt()
                    val popping = ceil(stats.air * 10.0 / 300.0).toInt() - full
                    for (index in 0 until full + popping) {
                        sprite(icons, if (index < full) 16 else 25, 18, 9, 9,
                            Offset((182f - 9f - index * 8f) * unit, (base - 10f) * unit), unit)
                    }
                }
                // Experience bar.
                sprite(icons, 0, 64, 182, 5, Offset(0f, (21f + extra) * unit), unit)
                val filled = (stats.experience.coerceIn(0f, 1f) * 183f).toInt()
                if (filled > 0) sprite(icons, 0, 69, filled, 5, Offset(0f, (21f + extra) * unit), unit)
            }
        }
        if (stats.level > 0) {
            Text(
                stats.level.toString(),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold,
                    shadow = Shadow(Color.Black, Offset(1.5f, 1.5f), 1f)),
                color = Color(0xFF80FF20),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Px * 4),
            )
        }
        if (icons == null) {
            Text("${stats.health.roundToInt()} ♥   ${stats.food} 🍗", style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/** The hotbar as a frosted bar of nine slots; the selected one is the primary container. */
@Composable
private fun Hotbar(frame: HudFrame) {
    val scheme = MaterialTheme.colorScheme
    val sprites = LocalHudSprites.current
    Surface(
        shape = RoundedCornerShape(Px * 5),
        color = scheme.surfaceContainer.copy(alpha = 0.6f),
        modifier = Modifier.hudGlass(Px * 5),
    ) {
        Row(Modifier.padding(Px * 1)) {
            for (slot in 0 until 9) {
                val selected = slot == frame.selected
                Box(
                    Modifier.size(Px * 20).clip(RoundedCornerShape(Px * 4))
                        .background(if (selected) scheme.primaryContainer.copy(alpha = 0.9f) else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    val item = frame.hotbar.getOrNull(slot)
                    if (item != null) {
                        ItemIcon(item, sprites)
                    }
                }
            }
        }
    }
}

/** An item's sprite at 16 vanilla pixels with its count and durability bar, as RenderItem.renderItemOverlays. */
@Composable
internal fun ItemIcon(item: HudItem, sprites: HudSprites?) {
    val bitmap = item.icon?.let { sprites?.texture(it) }
    // The game draws the animated glint over this rectangle on the GPU (HudGlint), so the layer stays cached.
    val slot = remember { glintAnchors++ }
    Box(Modifier.size(Px * 16)) {
        if (bitmap != null) {
            val anchor = if (item.glint) Modifier.sceneAnchor("glint:$slot:${item.glintStyle}:${item.icon}") else Modifier
            Canvas(Modifier.size(Px * 16).then(anchor)) {
                drawImage(bitmap, IntOffset.Zero, IntSize(bitmap.width, bitmap.height), IntOffset.Zero,
                    IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
            }
        }
        if (item.durability in 0f..0.999f) {
            Canvas(Modifier.size(Px * 16)) {
                val unit = size.width / 16f
                val hue = item.durability * 120f / 360f
                drawRect(Color.Black, Offset(2f * unit, 13f * unit), androidx.compose.ui.geometry.Size(13f * unit, 2f * unit))
                drawRect(Color.hsv(hue * 360f, 1f, 1f), Offset(2f * unit, 13f * unit),
                    androidx.compose.ui.geometry.Size(13f * unit * item.durability, unit))
            }
        }
        if (item.count > 1) {
            Text(
                item.count.toString(),
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.labelMedium.fontSize,
                    shadow = Shadow(Color.Black, Offset(1.5f, 1.5f), 1f)),
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd),
                maxLines = 1,
                overflow = TextOverflow.Visible,
            )
        }
    }
}

/** Distinguishes the glint anchors of item icons showing the same stack. */
private var glintAnchors = 0

/** Draws a texel rectangle of a pixel-art texture at [scale] screen pixels per texel, hard-edged. */
internal fun DrawScope.sprite(image: ImageBitmap, u: Int, v: Int, width: Int, height: Int, at: Offset, scale: Float) {
    if (u + width > image.width || v + height > image.height) return
    drawImage(
        image, IntOffset(u, v), IntSize(width, height), IntOffset(at.x.roundToInt(), at.y.roundToInt()),
        IntSize((width * scale).roundToInt(), (height * scale).roundToInt()), filterQuality = FilterQuality.None,
    )
}
