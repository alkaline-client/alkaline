package alkaline.ui.menu

import alkaline.renderer.vulkan.ui.glass
import alkaline.ui.hud.ContainerKind
import alkaline.ui.hud.ContainerView
import alkaline.ui.hud.HudFrame
import alkaline.ui.hud.HudItem
import alkaline.ui.hud.ItemIcon
import alkaline.ui.hud.LocalHudSprites
import alkaline.ui.hud.formatted
import alkaline.ui.hud.sprite
import alkaline.ui.theme.AlkalineIcons
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** One vanilla GUI pixel on container screens (vanilla's Normal GUI scale, as the HUD). */
private val Gui = 2.dp

/** Where a container's slots sit (an item's top left, in GUI pixels, by window slot) and the panel's size. */
private class ContainerLayout(val width: Int, val height: Int, val slots: List<IntOffset>, val hidden: Set<Int> = emptySet())

/** The player's 27 main slots and 9 hotbar slots, as every container places them. */
private fun playerSlots(x: Int, mainY: Int, hotbarY: Int): List<IntOffset> = List(36) { index ->
    if (index < 27) IntOffset(x + index % 9 * 18, mainY + index / 9 * 18) else IntOffset(x + (index - 27) * 18, hotbarY)
}

/** ContainerPlayer's slot positions. */
private val InventorySlots: List<IntOffset> = List(45) { slot ->
    when {
        slot == 0 -> IntOffset(144, 36)
        slot < 5 -> IntOffset(88 + (slot - 1) % 2 * 18, 26 + (slot - 1) / 2 * 18)
        slot < 9 -> IntOffset(8, 8 + (slot - 5) * 18)
        slot < 36 -> IntOffset(8 + (slot - 9) % 9 * 18, 84 + (slot - 9) / 9 * 18)
        else -> IntOffset(8 + (slot - 36) * 18, 142)
    }
}

/** Each container's slot positions and GuiContainer size, as its vanilla Container and Gui classes define them. */
private fun layoutOf(view: ContainerView): ContainerLayout {
    val standard = playerSlots(8, 84, 142)
    fun at(vararg points: Pair<Int, Int>) = points.map { IntOffset(it.first, it.second) }
    return when (view.kind) {
        ContainerKind.INVENTORY -> ContainerLayout(176, 166, InventorySlots)
        ContainerKind.CHEST -> {
            val rows = view.ownSlots / 9
            val shift = (rows - 4) * 18
            ContainerLayout(176, 114 + rows * 18,
                List(rows * 9) { IntOffset(8 + it % 9 * 18, 18 + it / 9 * 18) } + playerSlots(8, 103 + shift, 161 + shift))
        }
        ContainerKind.DISPENSER -> ContainerLayout(176, 166, List(9) { IntOffset(62 + it % 3 * 18, 17 + it / 3 * 18) } + standard)
        ContainerKind.HOPPER -> ContainerLayout(176, 133, List(view.ownSlots) { IntOffset(44 + it * 18, 20) } + playerSlots(8, 51, 109))
        ContainerKind.FURNACE -> ContainerLayout(176, 166, at(56 to 17, 56 to 53, 116 to 35) + standard)
        ContainerKind.BREWING_STAND -> ContainerLayout(176, 166, at(56 to 46, 79 to 53, 102 to 46, 79 to 17) + standard)
        ContainerKind.CRAFTING_TABLE -> ContainerLayout(176, 166,
            at(124 to 35) + List(9) { IntOffset(30 + it % 3 * 18, 17 + it / 3 * 18) } + standard)
        ContainerKind.ENCHANTING_TABLE -> ContainerLayout(176, 166, at(15 to 47, 35 to 47) + standard)
        ContainerKind.ANVIL -> ContainerLayout(176, 166, at(27 to 47, 76 to 47, 134 to 47) + standard)
        ContainerKind.BEACON -> ContainerLayout(230, 219, at(136 to 110) + playerSlots(36, 137, 195))
        ContainerKind.VILLAGER -> ContainerLayout(176, 166, at(36 to 53, 62 to 53, 120 to 53) + standard)
        ContainerKind.HORSE -> ContainerLayout(176, 166,
            at(8 to 18, 8 to 36) + (if (view.horseChest) List(15) { IntOffset(80 + it % 5 * 18, 18 + it / 5 * 18) } else emptyList()) + standard,
            hidden = if (view.horseArmor) emptySet() else setOf(1))
    }
}

/** Vanilla's empty armor slot outlines, helmet to boots. */
private val EmptyArmor = listOf("helmet", "chestplate", "leggings", "boots")

/**
 * A container screen: the player's inventory or a window the server opened (chests, furnaces, crafting tables,
 * enchanting tables, anvils, beacons, villagers, horses...). Every kind keeps vanilla's slot layout, so hands find
 * the same places, in one frosted panel with the kind's own controls drawn in Alkaline's components; the cursor
 * stack follows the pointer and the hovered item shows its tooltip. Clicks are not decided here: pointer input
 * goes to the client as slot events ([InventoryInput]), which applies GuiContainer's rules beside the real
 * container.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ContainerScreen(frame: HudFrame, input: InventoryInput?, onTyping: (Boolean) -> Unit) {
    val view = frame.container ?: return
    val layout = layoutOf(view)
    val density = LocalDensity.current.density
    val unit = Gui.value * density
    var panel by remember { mutableStateOf(Offset.Zero) }
    var pointer by remember { mutableStateOf<Offset?>(null) }
    val latestInput by rememberUpdatedState(input)
    val latestLayout by rememberUpdatedState(layout)
    val scheme = MaterialTheme.colorScheme
    val sprites = LocalHudSprites.current
    DisposableEffect(Unit) { onDispose { onTyping(false) } }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) {
            var hovered = Int.MIN_VALUE
            var held = -1
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    pointer = change.position
                    val slot = slotAt(change.position, panel, unit, latestLayout)
                    val target = latestInput
                    // A press a button or field inside the panel took is not a click on the window.
                    val taken = change.isConsumed
                    when (event.type) {
                        PointerEventType.Press -> if (!taken) {
                            val button = event.button?.index ?: 0
                            held = button
                            target?.press(slot, button)
                        }
                        PointerEventType.Release -> if (held >= 0) {
                            val button = event.button?.index ?: held
                            held = -1
                            target?.release(slot, button)
                        }
                        PointerEventType.Exit -> pointer = null
                        else -> {}
                    }
                    if (slot != hovered) {
                        hovered = slot
                        target?.hover(slot)
                    }
                    if (held >= 0 && slot >= 0 && event.type == PointerEventType.Move) {
                        target?.drag(slot)
                    }
                    event.changes.forEach { it.consume() }
                }
            }
        },
    ) {
        Surface(
            shape = RoundedCornerShape(Gui * 8),
            color = scheme.surfaceContainer.copy(alpha = backdropAlpha(0.78f)),
            modifier = Modifier.align(Alignment.Center).size(Gui * layout.width, Gui * layout.height).glass(Gui * 8)
                .onGloballyPositioned { panel = it.boundsInRoot().topLeft },
        ) {
            Box(Modifier.fillMaxSize()) {
                Decorations(view, input, onTyping)
                for (slot in layout.slots.indices) {
                    if (slot in layout.hidden) continue
                    val position = layout.slots[slot]
                    val item = view.slots.getOrNull(slot)
                    val dragged = slot in frame.dragged
                    val result = isResult(view.kind, slot)
                    Box(
                        Modifier.offset(Gui * (position.x - 1), Gui * (position.y - 1)).size(Gui * 18)
                            .background(
                                when {
                                    dragged -> scheme.primaryContainer.copy(alpha = 0.85f)
                                    result -> scheme.secondaryContainer.copy(alpha = 0.7f)
                                    else -> scheme.surfaceContainerHighest.copy(alpha = 0.55f)
                                },
                                RoundedCornerShape(Gui * 3),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item != null) {
                            ItemIcon(item, sprites)
                        } else if (view.kind == ContainerKind.INVENTORY && slot in 5..8) {
                            Outline("items/empty_armor_slot_${EmptyArmor[slot - 5]}")
                        }
                    }
                }
            }
        }
        val at = pointer
        val cursor = frame.cursor
        if (at != null && cursor != null) {
            Box(Modifier.offset { IntOffset((at.x - 8 * unit).roundToInt(), (at.y - 8 * unit).roundToInt()) }) {
                ItemIcon(cursor, sprites)
            }
        } else if (at != null && frame.tooltip.isNotEmpty()) {
            Tooltip(frame.tooltip, Modifier.offset { IntOffset((at.x + 12 * unit).roundToInt(), (at.y - 12 * unit).roundToInt()) })
        }
    }
}

/** Result slots, tinted as the crafting result is: nothing can be put into them. */
private fun isResult(kind: ContainerKind, slot: Int): Boolean = when (kind) {
    ContainerKind.INVENTORY, ContainerKind.CRAFTING_TABLE -> slot == 0
    ContainerKind.FURNACE, ContainerKind.ANVIL, ContainerKind.VILLAGER -> slot == 2
    else -> false
}

/** A faint item outline in an empty slot (armor, saddle). */
@Composable
private fun Outline(texture: String) {
    val outline = LocalHudSprites.current?.texture(texture) ?: return
    Canvas(Modifier.size(Gui * 16)) {
        drawImage(outline, IntOffset.Zero, IntSize(outline.width, outline.height), IntOffset.Zero,
            IntSize(size.width.toInt(), size.height.toInt()), alpha = 0.45f, filterQuality = FilterQuality.None)
    }
}

/** A label at a GUI pixel position. */
@Composable
private fun Label(text: String, x: Int, y: Int, width: Int = 160, align: TextAlign = TextAlign.Start, color: Color? = null) {
    val base = color ?: MaterialTheme.colorScheme.onSurfaceVariant
    Text(formatted(text, base), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = align, modifier = Modifier.offset(Gui * x, Gui * y).width(Gui * width))
}

/** The mirrored back arrow that points from ingredients to a result. */
@Composable
private fun Arrow(x: Int, y: Int, alpha: Float = 1f) {
    Icon(AlkalineIcons.Back, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        modifier = Modifier.offset(Gui * x, Gui * y).size(Gui * 14).graphicsLayer(scaleX = -1f))
}

/** A progress track at a GUI pixel rectangle, filled along its length (upwards when vertical). */
@Composable
private fun Progress(x: Int, y: Int, width: Int, height: Int, fraction: Float, vertical: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Canvas(Modifier.offset(Gui * x, Gui * y).size(Gui * width, Gui * height)) {
        val radius = androidx.compose.ui.geometry.CornerRadius(minOf(size.width, size.height) / 2)
        drawRoundRect(scheme.surfaceContainerHighest.copy(alpha = 0.8f), cornerRadius = radius)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            if (vertical) {
                drawRoundRect(scheme.primary, Offset(0f, size.height * (1 - f)), Size(size.width, size.height * f), radius)
            } else {
                drawRoundRect(scheme.primary, Offset.Zero, Size(size.width * f, size.height), radius)
            }
        }
    }
}

/** A small rounded button inside the panel, at a GUI pixel rectangle. */
@Composable
private fun PanelButton(
    x: Int, y: Int, width: Int, height: Int, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.offset(Gui * x, Gui * y).size(Gui * width, Gui * height)
            .alpha(if (enabled || selected) 1f else 0.38f)
            .background(
                when {
                    selected -> scheme.primaryContainer
                    enabled -> scheme.surfaceContainerHighest.copy(alpha = 0.85f)
                    else -> scheme.surfaceContainerHigh.copy(alpha = 0.4f)
                },
                RoundedCornerShape(Gui * 4),
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The kind's own controls and indicators, under its slots. */
@Composable
private fun Decorations(view: ContainerView, input: InventoryInput?, onTyping: (Boolean) -> Unit) {
    when (view.kind) {
        ContainerKind.INVENTORY -> {
            Label("Crafting", 88, 13)
            Arrow(126, 36)
        }
        ContainerKind.CHEST, ContainerKind.DISPENSER, ContainerKind.HOPPER -> Label(view.title, 8, 5)
        ContainerKind.CRAFTING_TABLE -> {
            Label(view.title, 28, 5)
            Arrow(94, 36)
        }
        ContainerKind.FURNACE -> {
            Label(view.title, 8, 5, width = 160, align = TextAlign.Center)
            // GuiFurnace: the flame burns down as fuel runs out, the arrow fills as the item cooks.
            Progress(61, 37, 6, 14, view.fuel, vertical = true)
            Progress(79, 39, 24, 6, view.progress)
        }
        ContainerKind.BREWING_STAND -> {
            Label(view.title, 8, 5, width = 160, align = TextAlign.Center)
            Progress(98, 17, 7, 27, view.progress, vertical = true)
        }
        ContainerKind.ENCHANTING_TABLE -> Enchanting(view, input)
        ContainerKind.ANVIL -> Anvil(view, input, onTyping)
        ContainerKind.BEACON -> Beacon(view, input)
        ContainerKind.VILLAGER -> Villager(view, input)
        ContainerKind.HORSE -> {
            Label(view.title, 8, 5)
            if (view.slots.getOrNull(0) == null) {
                Box(Modifier.offset(Gui * 8, Gui * 18)) { Outline("items/saddle") }
            }
        }
    }
}

/** GuiEnchantment's three offers: the level cost, the hint of what the item gets, chosen with a click. */
@Composable
private fun Enchanting(view: ContainerView, input: InventoryInput?) {
    val scheme = MaterialTheme.colorScheme
    Label(view.title, 8, 5)
    for ((option, offer) in view.enchantments.withIndex()) {
        val y = 14 + 19 * option
        val shown = offer.levels > 0
        PanelButton(60, y, 108, 18, enabled = shown && offer.enabled, onClick = { input?.enchant(option) }) {
            if (shown) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Gui * 4), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatted(offer.hint, scheme.onSurface), style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${offer.levels}", style = MaterialTheme.typography.labelLarge,
                        color = if (offer.enabled) scheme.primary else scheme.error)
                    Text("  ${option + 1}✦", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** GuiRepair: the name field and the cost in levels. */
@Composable
private fun Anvil(view: ContainerView, input: InventoryInput?, onTyping: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Label(view.title, 60, 5)
    var name by remember(view.itemName) { mutableStateOf(view.itemName ?: "") }
    val enabled = view.itemName != null
    // GuiRepair.sendSlotContents: a new input item starts the field from its name, and the anvil forgets the old one.
    LaunchedEffect(view.itemName, view.itemDefaultName) {
        if (view.itemName != null) input?.rename(if (view.itemName == view.itemDefaultName) "" else view.itemName)
    }
    Box(
        Modifier.offset(Gui * 59, Gui * 20).size(Gui * 110, Gui * 16)
            .background(scheme.surfaceContainerHighest.copy(alpha = if (enabled) 0.9f else 0.4f), RoundedCornerShape(Gui * 4))
            .padding(horizontal = Gui * 4),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = name,
            onValueChange = { next ->
                val text = next.take(30)
                if (text != name) {
                    name = text
                    // GuiRepair.renameItem: the item's own name means no custom name.
                    input?.rename(if (text == view.itemDefaultName) "" else text)
                }
            },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.fillMaxWidth().onFocusChanged { onTyping(it.isFocused) },
        )
    }
    // GuiRepair: the cost shows with a result, or as "Too Expensive!" when the anvil refuses.
    if (view.repairCost > 0 && (view.slots.getOrNull(2) != null || view.repairTooExpensive)) {
        val text = if (view.repairTooExpensive) "Too Expensive!" else "Enchantment Cost: ${view.repairCost}"
        Label(text, 8, 67, width = 160, align = TextAlign.End,
            color = if (view.repairAffordable && !view.repairTooExpensive) scheme.primary else scheme.error)
    }
    Arrow(103, 48)
}

/** GuiBeacon's tiers of powers: speed and haste, resistance and jump boost, strength; then regeneration or level II. */
private val BeaconTiers = listOf(listOf(1, 3), listOf(11, 8), listOf(5), listOf(10))

/** Potion.setIconIndex: column + row * 8 in inventory.png's status icons. */
private val EffectIcons = mapOf(1 to 0, 3 to 2, 5 to 4, 8 to 10, 10 to 7, 11 to 14)

@Composable
private fun Beacon(view: ContainerView, input: InventoryInput?) {
    var primary by remember(view.beaconPrimary) { mutableIntStateOf(view.beaconPrimary) }
    var secondary by remember(view.beaconSecondary) { mutableIntStateOf(view.beaconSecondary) }
    Label("Primary Power", 12, 8, width = 100, align = TextAlign.Center)
    Label("Secondary Power", 119, 8, width = 100, align = TextAlign.Center)
    for (tier in 0..2) {
        val effects = BeaconTiers[tier]
        val span = effects.size * 22 + (effects.size - 1) * 2
        for ((index, effect) in effects.withIndex()) {
            PanelButton(76 + index * 24 - span / 2, 22 + tier * 25, 22, 22, enabled = view.beaconLevels > tier,
                selected = primary == effect, onClick = { primary = effect }) { EffectIcon(effect) }
        }
    }
    // The fourth tier: regeneration, or the primary power at level II.
    val second = listOfNotNull(10, primary.takeIf { it > 0 })
    val span = second.size * 22 + (second.size - 1) * 2
    for ((index, effect) in second.withIndex()) {
        PanelButton(167 + index * 24 - span / 2, 47, 22, 22, enabled = view.beaconLevels >= 4 && primary > 0,
            selected = secondary == effect, onClick = { secondary = effect }) { EffectIcon(effect) }
    }
    val paid = view.slots.getOrNull(0) != null
    PanelButton(164, 107, 22, 22, enabled = paid && primary > 0, onClick = { input?.beacon(primary, secondary) }) {
        Icon(AlkalineIcons.Check, "Confirm", Modifier.size(Gui * 12), tint = MaterialTheme.colorScheme.primary)
    }
    Label("Payment", 60, 113, width = 70, align = TextAlign.End)
}

@Composable
private fun EffectIcon(effect: Int) {
    val inventory = LocalHudSprites.current?.texture("gui/container/inventory")
    val index = EffectIcons[effect] ?: return
    if (inventory == null) return
    Canvas(Modifier.size(Gui * 18)) {
        sprite(inventory, index % 8 * 18, 198 + index / 8 * 18, 18, 18, Offset.Zero, size.width / 18f)
    }
}

/** GuiMerchant: the trade on show above its slots, with arrows to page through the villager's trades. */
@Composable
private fun Villager(view: ContainerView, input: InventoryInput?) {
    val sprites = LocalHudSprites.current
    Label(view.title, 8, 5, width = 160, align = TextAlign.Center)
    val trade = view.trades.getOrNull(view.trade)
    PanelButton(17, 23, 12, 19, enabled = view.trade > 0, onClick = { input?.selectTrade(view.trade - 1) }) {
        Icon(AlkalineIcons.Back, "Previous trade", Modifier.size(Gui * 8))
    }
    PanelButton(147, 23, 12, 19, enabled = view.trade < view.trades.size - 1, onClick = { input?.selectTrade(view.trade + 1) }) {
        Icon(AlkalineIcons.Back, "Next trade", Modifier.size(Gui * 8).graphicsLayer(scaleX = -1f))
    }
    if (trade != null) {
        @Composable
        fun Ghost(item: HudItem?, x: Int) {
            if (item != null) {
                Box(Modifier.offset(Gui * x, Gui * 24).alpha(0.75f)) { ItemIcon(item, sprites) }
            }
        }
        Ghost(trade.first, 36)
        Ghost(trade.second, 62)
        Ghost(trade.result, 120)
        if (trade.disabled) {
            Icon(AlkalineIcons.Close, "Out of stock", tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.offset(Gui * 86, Gui * 25).size(Gui * 14))
        } else {
            Arrow(86, 25, alpha = 0.6f)
        }
    }
    Arrow(87, 54)
}

/** GuiScreen.renderToolTip's content in a calm card: the name line, then gray detail lines. */
@Composable
internal fun Tooltip(lines: List<String>, modifier: Modifier) {
    val base = MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
        modifier = modifier.widthIn(max = 320.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            for ((index, line) in lines.withIndex()) {
                Text(formatted(line, base),
                    style = if (index == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The window slot under a point (GuiContainer.getSlotAtPosition), -1 inside the panel, -999 outside it. */
private fun slotAt(point: Offset, panel: Offset, unit: Float, layout: ContainerLayout): Int {
    val x = (point.x - panel.x) / unit
    val y = (point.y - panel.y) / unit
    if (x < 0f || y < 0f || x >= layout.width || y >= layout.height) return -999
    for (slot in layout.slots.indices) {
        if (slot in layout.hidden) continue
        val position = layout.slots[slot]
        if (x >= position.x - 1 && x < position.x + 17 && y >= position.y - 1 && y < position.y + 17) return slot
    }
    return -1
}

/** GuiEditSign: four lines on a calm card, written by the player and sent when it closes. */
@Composable
internal fun SignEditor(controller: MenuController) {
    val scheme = MaterialTheme.colorScheme
    DisposableEffect(Unit) {
        controller.typing = true
        onDispose { controller.typing = false }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = scheme.surfaceContainer.copy(alpha = backdropAlpha(0.82f)),
            modifier = Modifier.width(360.dp).glass(24.dp),
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Edit sign message", style = MaterialTheme.typography.titleMedium)
                val focus = remember { FocusRequester() }
                for (line in 0 until 4) {
                    Box(
                        Modifier.padding(top = if (line == 0) 16.dp else 6.dp).fillMaxWidth()
                            .background(scheme.surfaceContainerHighest.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        BasicTextField(
                            value = controller.signLines[line],
                            onValueChange = { next ->
                                controller.signLines = controller.signLines.toMutableList().also { it[line] = next.take(15) }
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, textAlign = TextAlign.Center),
                            cursorBrush = SolidColor(scheme.primary),
                            modifier = Modifier.fillMaxWidth().let { if (line == 0) it.focusRequester(focus) else it },
                        )
                    }
                }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(Modifier.padding(top = 18.dp)) {
                    HoverAction(AlkalineIcons.Check, "Done", 40.dp, Modifier, onClick = { controller.closeOverlay() })
                }
            }
        }
    }
}
