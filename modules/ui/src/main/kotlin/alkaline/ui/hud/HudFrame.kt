package alkaline.ui.hud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** An item in a HUD slot: its sprite (path under textures/, without .png), stack size, wear and glint. */
data class HudItem(
    val icon: String?,
    val count: Int,
    /** Remaining durability 0..1, or -1 for items that do not wear. */
    val durability: Float = -1f,
    val glint: Boolean = false,
    /** Which glint the game draws over the icon (see the game's ItemCatalog.glintStyle), when [glint] is set. */
    val glintStyle: Int = 0,
)

/** An active potion effect: vanilla's English name, level (1 = I), ticks left and its inventory.png icon. */
data class HudEffect(val name: String, val level: Int, val ticks: Int, val iconIndex: Int)

/** One sidebar line (§ formatted) and its score. */
data class HudScore(val text: String, val score: Int)

/** A player list row: name (§ formatted), latency in ms, and whether they spectate. */
data class HudPlayer(val name: String, val ping: Int, val spectator: Boolean)

/** A chat line (§ formatted) and how long ago it arrived, in ticks. */
data class HudChat(
    val text: String,
    val ageTicks: Long,
    /** Arrival order; unique within a session. */
    val serial: Long = 0,
    val actions: List<HudChatAction> = emptyList(),
)

/**
 * What the visible characters [start] until [end] of a chat line do: [click] (`run_command`, `suggest_command` or
 * `open_url`) with [value], a [hover] text (§ formatted), and the [insertion] Shift-click types.
 */
data class HudChatAction(
    val start: Int,
    val end: Int,
    val click: String?,
    val value: String?,
    val hover: String?,
    val insertion: String?,
)

/** The vanilla screen an open container uses: the player's inventory, or one of the windows servers open. */
enum class ContainerKind {
    INVENTORY, CHEST, DISPENSER, HOPPER, FURNACE, BREWING_STAND, CRAFTING_TABLE, ENCHANTING_TABLE, ANVIL, BEACON,
    VILLAGER, HORSE,
}

/** One enchanting table offer: its level cost, the hint (§ formatted), and whether it can be taken now. */
data class EnchantOffer(val levels: Int, val hint: String, val enabled: Boolean)

/** One villager trade: what it costs, what it gives, and whether it is used up for now. */
data class TradeOffer(val first: HudItem?, val second: HudItem?, val result: HudItem?, val disabled: Boolean)

/**
 * The open container screen: which kind, its title (§ formatted), and every slot (its own first, then the
 * player's 27 main and 9 hotbar slots; the inventory is its 45 window slots), drag previews applied. The other
 * fields are the kind's own state; they keep their defaults elsewhere.
 */
data class ContainerView(
    val kind: ContainerKind,
    val title: String,
    val slots: List<HudItem?>,
    /** Slots before the player's 36 (all 45 for the inventory). */
    val ownSlots: Int,
    /** Furnace cooking or brewing progress 0..1. */
    val progress: Float = 0f,
    /** Furnace fuel left 0..1 (0 when not burning). */
    val fuel: Float = 0f,
    val enchantments: List<EnchantOffer> = emptyList(),
    /** Anvil cost in levels (0 for none), whether the player can pay it, and the name the result gets. */
    val repairCost: Int = 0,
    val repairAffordable: Boolean = true,
    val repairTooExpensive: Boolean = false,
    /** The anvil input's name, to start the rename field from; null while it is empty. */
    val itemName: String? = null,
    /** The anvil input's own name without a custom one (renaming to it clears the custom name). */
    val itemDefaultName: String? = null,
    val trades: List<TradeOffer> = emptyList(),
    val trade: Int = 0,
    /** Beacon pyramid levels 0..4 and the effect ids in force (0 for none). */
    val beaconLevels: Int = 0,
    val beaconPrimary: Int = 0,
    val beaconSecondary: Int = 0,
    val horseChest: Boolean = false,
    val horseArmor: Boolean = false,
)

/**
 * Everything the in-game HUD shows, as one immutable value the client publishes on the UI thread (at most once
 * per tick, and only when something changed). Text fields carry Minecraft's § formatting codes.
 */
data class HudFrame(
    val fps: Int = 0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    /** The look direction's yaw in degrees (0 = south, 90 = west, as Minecraft). */
    val yaw: Float = 0f,
    /** Horizontal speed in blocks per second. */
    val speed: Float = 0f,
    val ping: Int = -1,
    val server: String = "",
    val leftCps: Int = 0,
    val rightCps: Int = 0,
    /** Held inputs, see the KEY_ constants. */
    val keys: Int = 0,
    /** Labels for the keystrokes keys: forward, left, back, right, jump. */
    val keyLabels: List<String> = listOf("W", "A", "S", "D", "Space"),
    val health: Float = 20f,
    val maxHealth: Float = 20f,
    /** Ticks left of the hurt flash (the hearts blink while hurt). */
    val hurtTicks: Int = 0,
    val food: Int = 20,
    /** Food saturation; at zero the food icons shake. */
    val saturation: Float = 5f,
    /** Absorption (golden hearts), in half hearts. */
    val absorption: Float = 0f,
    /** Effects that change the status icons: 1 poison, 2 wither (hearts); hunger (food); regeneration (a wave). */
    val heartEffect: Int = 0,
    val hungerEffect: Boolean = false,
    val regeneration: Boolean = false,
    /** Riding: vanilla hides the food bar. */
    val riding: Boolean = false,
    val armor: Int = 0,
    /** Air ticks, 300 when full; bubbles show below that. */
    val air: Int = 300,
    val experience: Float = 0f,
    val level: Int = 0,
    /** 0 survival, 1 creative, 2 adventure, 3 spectator. */
    val gameMode: Int = 0,
    val hotbar: List<HudItem?> = List(9) { null },
    val selected: Int = 0,
    val effects: List<HudEffect> = emptyList(),
    val sidebarTitle: String? = null,
    val sidebar: List<HudScore> = emptyList(),
    val chat: List<HudChat> = emptyList(),
    val title: String = "",
    val subtitle: String = "",
    val titleAlpha: Float = 0f,
    val actionBar: String = "",
    val actionBarAlpha: Float = 0f,
    val players: List<HudPlayer> = emptyList(),
    /** Whether the player list is held open (Tab). */
    val showPlayers: Boolean = false,
    /** How the player moves for the toggle sprint & sneak readout ("Sprinting (Toggled)"...), "" when walking. */
    val movementStatus: String = "",
    /** The player list's header and footer (§ formatted, "" for none). */
    val playerListHeader: String = "",
    val playerListFooter: String = "",
    /** The current tick, for chat fading. */
    val tick: Long = 0,
    /** The open container screen, or null. */
    val container: ContainerView? = null,
    /** The stack on the cursor while a container is open. */
    val cursor: HudItem? = null,
    /** Slots a drag in progress covers. */
    val dragged: Set<Int> = emptySet(),
    /** Tooltip lines (§ formatted) of the hovered slot's item while the cursor is empty. */
    val tooltip: List<String> = emptyList(),
) {
    companion object {
        const val KEY_FORWARD = 1
        const val KEY_LEFT = 2
        const val KEY_BACK = 4
        const val KEY_RIGHT = 8
        const val KEY_JUMP = 16
        const val KEY_ATTACK = 32
        const val KEY_USE = 64

        /** What the HUD editor shows when the game has nothing to show yet, so every element can be placed. */
        @JvmField
        val SAMPLE = HudFrame(
            fps = 240, x = 128.5, y = 64.0, z = -42.3, yaw = 135f, speed = 5.6f, ping = 42,
            server = "play.example.net", leftCps = 12, rightCps = 4, keys = KEY_FORWARD or KEY_ATTACK,
            health = 15f, food = 17, armor = 14, air = 200, experience = 0.4f, level = 12,
            hotbar = listOf(
                HudItem("items/diamond_sword", 1, 0.8f, true), HudItem("items/bow", 1), HudItem("items/golden_apple", 6),
                HudItem("items/ender_pearl", 16), null, HudItem("items/water_bucket", 1), null, null,
                HudItem("items/cooked_beef", 64),
            ),
            selected = 0,
            effects = listOf(HudEffect("Speed", 2, 1820, 0), HudEffect("Strength", 1, 600, 4)),
            sidebarTitle = "§e§lALKALINE", sidebar = listOf(HudScore("§7Kills: §a12", 3), HudScore("§7Map: §fCastle", 2),
                HudScore("§bplay.example.net", 1)),
            chat = listOf(HudChat("§e[Server] §fWelcome to Alkaline.", 0), HudChat("§7Steve: gg", 0)),
            movementStatus = "Sprinting (Toggled)",
        )
    }
}

/** The HUD's live values; the client replaces [frame] from the UI thread. */
class HudState {
    var frame by mutableStateOf(HudFrame())
}
