package alkaline.clientapp

import alkaline.client.settings.ClientSettings
import alkaline.config.ConfigSnapshot
import alkaline.game.inventory.Inventory
import alkaline.game.inventory.ItemStack
import alkaline.game.inventory.Items
import alkaline.game.inventory.Window
import alkaline.ui.hud.ContainerKind
import alkaline.ui.hud.ContainerView
import alkaline.ui.hud.EnchantOffer
import alkaline.ui.hud.HudChat
import alkaline.ui.hud.HudChatAction
import alkaline.ui.hud.HudEffect
import alkaline.ui.hud.HudFrame
import alkaline.ui.hud.HudItem
import alkaline.ui.hud.HudPlayer
import alkaline.ui.hud.HudScore
import alkaline.ui.hud.HudSprites
import alkaline.ui.hud.TradeOffer
import kotlin.math.sqrt

/**
 * Builds the HUD's [HudFrame] from the game state on the render thread, once per tick: the player's vitals and
 * inventory, effects, scoreboard, chat, titles, the player list and the readouts modules show. Item icons come
 * from the [ItemCatalog] the world draws with.
 */
class HudBridge(private val catalog: ItemCatalog, private val sprites: HudSprites, private val chatText: ChatText) {
    private val rendered = HashMap<String, alkaline.renderer.image.RgbaImage>()
    private val names = ItemNames(chatText)

    private var chatOwner: PlaySession? = null
    private var chatVersion = -1
    private var chatCache = emptyList<HudChat>()

    /** Every kept chat line, rebuilt only when one arrives: the open chat scrolls through all of them. */
    private fun chatLines(play: PlaySession): List<HudChat> {
        if (play !== chatOwner || play.chatVersion() != chatVersion) {
            chatOwner = play
            chatVersion = play.chatVersion()
            chatCache = play.chat().map { line ->
                HudChat(line.text(), line.tick(), line.serial(), line.actions().map {
                    HudChatAction(it.start(), it.end(), it.click(), it.value(), it.hover(), it.insertion())
                })
            }
        }
        return chatCache
    }

    fun frame(
        play: PlaySession, settings: ConfigSnapshot, fps: Int, server: String, leftCps: Int, rightCps: Int,
        keys: Int, showPlayers: Boolean, chat: ChatText, movementStatus: String = "",
    ): HudFrame {
        val player = play.player()
        val inventory = play.inventory()
        val dx = player.x - player.previousX
        val dz = player.z - player.previousZ
        val sidebar = play.scoreboard().sidebar()
        val listed = play.players()
        return HudFrame(
            fps = fps,
            x = player.x,
            y = player.y,
            z = player.z,
            yaw = player.yaw,
            speed = (sqrt(dx * dx + dz * dz) * 20.0).toFloat(),
            ping = listed.get(play.self())?.ping() ?: -1,
            server = server,
            leftCps = leftCps,
            rightCps = rightCps,
            keys = keys,
            keyLabels = listOf(
                ClientSettings.KEY_FORWARD, ClientSettings.KEY_LEFT, ClientSettings.KEY_BACK, ClientSettings.KEY_RIGHT,
                ClientSettings.KEY_JUMP,
            ).map { label(settings.get(it)) },
            health = player.health,
            hurtTicks = player.hurtTime,
            food = player.food,
            saturation = play.saturation(),
            maxHealth = play.maxHealth(),
            absorption = play.absorption(),
            heartEffect = if (play.amplifier(19) >= 0) 1 else if (play.amplifier(20) >= 0) 2 else 0,
            hungerEffect = play.amplifier(17) >= 0,
            regeneration = play.amplifier(10) >= 0,
            riding = play.riding(),
            armor = inventory.armorPoints(),
            air = play.air(),
            experience = play.experience(),
            level = play.level(),
            gameMode = play.gameMode(),
            hotbar = List(9) { slot -> item(inventory.hotbar(slot)) },
            selected = inventory.selected(),
            effects = play.effects().values.map { effect ->
                HudEffect(EFFECT_NAMES.getOrElse(effect.id()) { "Effect ${effect.id()}" }, effect.amplifier() + 1,
                    effect.ticksLeft(), EFFECT_ICONS.getOrElse(effect.id()) { -1 })
            },
            sidebarTitle = sidebar?.title(),
            sidebar = sidebar?.lines()?.map { HudScore(it.text(), it.score()) } ?: emptyList(),
            chat = chatLines(play),
            title = play.title(),
            subtitle = play.subtitle(),
            titleAlpha = play.titleAlpha(0f),
            actionBar = play.actionBar(),
            actionBarAlpha = play.actionBarAlpha(0f),
            players = if (showPlayers) listed.sorted(play.scoreboard()).take(80).map { entry ->
                HudPlayer(entry.displayNameJson()?.let(chat::legacy) ?: play.scoreboard().format(entry.name() ?: "?"),
                    entry.ping(), entry.gameMode() == 3)
            } else emptyList(),
            showPlayers = showPlayers,
            tick = play.ticks(),
            container = if (play.containerOpen()) container(play) else null,
            cursor = if (play.containerOpen()) cursorItem(play) else null,
            dragged = if (play.containerOpen()) dragged(play) else emptySet(),
            tooltip = if (play.containerOpen()) tooltip(play) else emptyList(),
            movementStatus = movementStatus,
            playerListHeader = play.playerListHeader(),
            playerListFooter = play.playerListFooter(),
        )
    }

    /** The open container's screen state: its slots (drag previews applied) and the kind's own values. */
    private fun container(play: PlaySession): ContainerView? {
        val container = play.container() ?: return null
        val preview = play.windowInput().dragPreview()
        val slots = List(container.size()) { slot -> item(preview?.getOrNull(slot) ?: container.slot(slot)) }
        val window = play.window() ?: return ContainerView(ContainerKind.INVENTORY, "", slots, Inventory.SLOTS)
        val creative = play.gameMode() == 1
        val base = ContainerView(kind(window.type()), chatText.legacy(window.titleJson()), slots, window.ownSlots())
        return when (window.type()) {
            Window.Type.FURNACE -> {
                // GuiFurnace.getBurnLeftScaled and getCookProgressScaled.
                val burnTotal = window.property(1).takeIf { it != 0 } ?: 200
                val cook = window.property(2)
                val total = window.property(3)
                base.copy(fuel = (window.property(0).toFloat() / burnTotal).coerceIn(0f, 1f),
                    progress = if (total != 0 && cook != 0) cook.toFloat() / total else 0f)
            }
            Window.Type.BREWING_STAND -> {
                val brew = window.property(0)
                base.copy(progress = if (brew > 0) 1f - brew / 400f else 0f)
            }
            Window.Type.ENCHANTING_TABLE -> base.copy(enchantments = List(3) { option ->
                val hint = window.property(4 + option)
                val name = if (hint >= 0) names.enchantment(hint and 255, hint shr 8 and 255) else null
                val clue = name?.let { (chatText.translation("container.enchant.clue") ?: "%s . . . ?").replace("%s", it) }
                EnchantOffer(window.property(option), "§o" + (clue ?: ""), window.canEnchant(option))
            })
            Window.Type.ANVIL -> {
                val input = window.slot(0)
                val cost = window.repairCost()
                base.copy(
                    repairCost = cost,
                    repairAffordable = creative || play.level() >= cost,
                    repairTooExpensive = cost >= 40 && !creative,
                    itemName = input?.let { names.name(it) },
                    itemDefaultName = input?.let { names.name(ItemStack(it.id(), it.count(), it.damage(), null)) },
                )
            }
            Window.Type.VILLAGER -> base.copy(
                trades = window.trades().map { TradeOffer(item(it.first()), item(it.second()), item(it.result()), it.disabled()) },
                trade = window.tradeIndex(),
            )
            Window.Type.BEACON -> base.copy(beaconLevels = window.property(0), beaconPrimary = window.property(1),
                beaconSecondary = window.property(2))
            Window.Type.HORSE -> base.copy(horseChest = window.horseChest(), horseArmor = window.horseArmor())
            else -> base
        }
    }

    private fun kind(type: Window.Type): ContainerKind = when (type) {
        Window.Type.CHEST -> ContainerKind.CHEST
        Window.Type.DISPENSER -> ContainerKind.DISPENSER
        Window.Type.HOPPER -> ContainerKind.HOPPER
        Window.Type.FURNACE -> ContainerKind.FURNACE
        Window.Type.BREWING_STAND -> ContainerKind.BREWING_STAND
        Window.Type.CRAFTING_TABLE -> ContainerKind.CRAFTING_TABLE
        Window.Type.ENCHANTING_TABLE -> ContainerKind.ENCHANTING_TABLE
        Window.Type.ANVIL -> ContainerKind.ANVIL
        Window.Type.BEACON -> ContainerKind.BEACON
        Window.Type.VILLAGER -> ContainerKind.VILLAGER
        Window.Type.HORSE -> ContainerKind.HORSE
    }

    private fun dragged(play: PlaySession): Set<Int> {
        val preview = play.windowInput().dragPreview() ?: return emptySet()
        return preview.indices.filterTo(HashSet()) { preview[it] != null }
    }

    private fun tooltip(play: PlaySession): List<String> {
        val container = play.container() ?: return emptyList()
        val hovered = play.windowInput().hovered()
        if (container.cursor() != null || hovered < 0) return emptyList()
        val stack = container.slot(hovered) ?: return emptyList()
        return names.tooltip(stack)
    }

    /** The cursor stack, showing what stays on it while a drag spreads the rest. */
    private fun cursorItem(play: PlaySession): HudItem? {
        val cursor = play.inventory().cursor() ?: return null
        val left = play.windowInput().dragRemainder()
        return if (left == 0) null else item(if (left > 0) cursor.withCount(left) else cursor)
    }

    private fun item(stack: ItemStack?): HudItem? {
        stack ?: return null
        val max = Items.maxDamage(stack.id())
        val durability = if (max > 0 && stack.damage() > 0) (1f - stack.damage().toFloat() / max).coerceIn(0f, 1f) else -1f
        val glint = catalog.glintStyle(stack)
        return HudItem(icon(stack), stack.count(), durability, glint >= 0, glint)
    }

    /**
     * The icon of a stack as the inventory draws it: block items as 3D blocks, generated items with their tinted
     * layers. Null for the tick or two while its mesh is queued.
     */
    private fun icon(stack: ItemStack): String? {
        val key = catalog.icon(stack, rendered) ?: return null
        if (rendered.isNotEmpty()) {
            rendered.forEach(sprites::put)
            rendered.clear()
        }
        return key
    }

    private fun label(binding: String): String = when {
        binding == "none" -> "—"
        binding.startsWith("key:") -> binding.substring(4).let { if (it == "Space") "Space" else it }
        else -> binding.substringAfter(':').uppercase()
    }

    private companion object {
        /** Potion names (en_US) by 1.8.9 id. */
        val EFFECT_NAMES = mapOf(
            1 to "Speed", 2 to "Slowness", 3 to "Haste", 4 to "Mining Fatigue", 5 to "Strength",
            6 to "Instant Health", 7 to "Instant Damage", 8 to "Jump Boost", 9 to "Nausea", 10 to "Regeneration",
            11 to "Resistance", 12 to "Fire Resistance", 13 to "Water Breathing", 14 to "Invisibility",
            15 to "Blindness", 16 to "Night Vision", 17 to "Hunger", 18 to "Weakness", 19 to "Poison", 20 to "Wither",
            21 to "Health Boost", 22 to "Absorption", 23 to "Saturation",
        )

        /** Potion.setIconIndex: column + row * 8 in inventory.png's status icons. */
        val EFFECT_ICONS = mapOf(
            1 to 0, 2 to 1, 3 to 2, 4 to 3, 5 to 4, 8 to 10, 9 to 11, 10 to 7, 11 to 14, 12 to 15, 13 to 16,
            14 to 8, 15 to 13, 16 to 12, 17 to 9, 18 to 5, 19 to 6, 20 to 17, 21 to 18, 22 to 18,
        )
    }
}
