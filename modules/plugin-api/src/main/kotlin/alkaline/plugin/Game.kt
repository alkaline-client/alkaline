package alkaline.plugin

import java.util.UUID

/**
 * The 1.8.9 game: its state, its events, and control over the local player.
 *
 * Threads: every listener runs on the game's render thread between frames, so it must return quickly and must not
 * block, or the game stutters. State is read on that thread too: [player], [inventory], [world], [entities],
 * [target] and [server] throw [IllegalStateException] elsewhere (from a coroutine, use [schedule]). Actions
 * ([sendChat], [attack], [look] and the rest) work from any thread: off the render thread they are queued and
 * applied there in order. An action given bad arguments (a non-finite angle or volume, a chat message the server
 * would kick for) throws [IllegalArgumentException] to its caller at once, on any thread. A listener that throws
 * stops its plugin. A listener that holds the render thread for seconds is reported to the player as not
 * responding, with its stack in the log.
 */
interface Game {
    /** Whether a game is in progress (from joining until leaving). Any thread. */
    val inGame: Boolean

    // ---- lifecycle ----------------------------------------------------------------------------------------

    /** The player joined [address] (as typed; the world is still loading). */
    fun onJoin(listener: (address: String) -> Unit): Registration

    /** The player left the game; [reason] is the server's disconnect message, or null when they left. */
    fun onLeave(listener: (reason: String?) -> Unit): Registration

    /** Every game tick (20 per second) while in a game, after the game's own tick. */
    fun onTick(listener: () -> Unit): Registration

    /** Runs [task] on the render thread after [ticks] game ticks (0: the next tick). Any thread. */
    fun schedule(ticks: Int = 0, task: () -> Unit): Registration

    // ---- state (render thread) ----------------------------------------------------------------------------

    fun player(): PlayerState?
    fun inventory(): InventoryState?
    fun world(): World?
    /** Every entity the client tracks, the local player excluded. */
    fun entities(): List<EntityState>
    fun entity(id: Int): EntityState?
    /** What the crosshair points at within reach. */
    fun target(): Target?
    fun server(): ServerState?

    /**
     * Where the world point ([x], [y], [z]) shows in the game view, as fractions of its width and height from the
     * top left; null behind the camera or outside a game. Uses the last drawn frame's camera. Any thread; for an
     * [PluginContext.overlay] that follows the camera, read it every frame (`withFrameNanos`).
     */
    fun project(x: Double, y: Double, z: Double): ScreenPoint?

    // ---- chat ---------------------------------------------------------------------------------------------

    /** Each chat message from the server (not the action bar) before it is shown; it may be hidden or replaced. */
    fun onChat(listener: (ChatMessage) -> Unit): Registration

    /**
     * Sends a chat message or a server command ("/..."), as the player typing it would: 1 to 100 characters, not
     * blank, without `§` or control characters (the 1.8.9 server kicks for those); otherwise [IllegalArgumentException].
     */
    fun sendChat(message: String)

    /** Adds a line to the player's own chat (§ formatting codes work); the server never sees it. */
    fun showChat(text: String)

    /**
     * Handles the command `/[name]` the player types, instead of sending it to the server. Names are
     * `[a-z0-9_-]{1,32}`; a name another plugin already handles throws [IllegalStateException].
     */
    fun command(name: String, description: String = "", handler: (CommandCall) -> Unit): Registration

    // ---- input --------------------------------------------------------------------------------------------

    /** Each attack and use press in game, before the game acts on it. */
    fun onClick(listener: (Click) -> Unit): Registration

    /**
     * Each key press while playing (the mouse captured), before the game acts on it. Text typed into chat or a
     * screen's fields never arrives here. Alkaline's own Escape, ClickGUI, inventory and chat keys are not offered.
     */
    fun onKey(listener: (KeyPress) -> Unit): Registration

    /** Each press of the input [binding] holds (a key setting of the plugin's) while playing. */
    fun onPress(binding: PluginSetting<String>, listener: () -> Unit): Registration

    /** Each tick's movement input before the player moves; change it to steer. Runs even while a screen is open. */
    fun onMovement(listener: (Movement) -> Unit): Registration

    /** Presses attack once, as a click would (swing, hit the entity under the crosshair, or start digging). */
    fun attack()

    /** Presses use once (use the held item, place a block, interact with an entity). */
    fun use()

    /** Selects hotbar slot [slot] (0..8). */
    fun select(slot: Int)

    /** Turns the player to [yaw] and [pitch] in Minecraft's degrees (see [PlayerState.yaw]). */
    fun look(yaw: Float, pitch: Float)

    // ---- sound --------------------------------------------------------------------------------------------

    /** Plays a 1.8.9 sound event (such as `random.orb`) without position. */
    fun playSound(sound: String, volume: Float = 1f, pitch: Float = 1f)

    /** Plays a 1.8.9 sound event at a world position. */
    fun playSound(sound: String, x: Double, y: Double, z: Double, volume: Float = 1f, pitch: Float = 1f)

    // ---- network ------------------------------------------------------------------------------------------

    /** Each plugin message (Custom Payload) the server sends on [channel]. */
    fun onMessage(channel: String, listener: (ByteArray) -> Unit): Registration

    /** Sends a plugin message on [channel] (at most 20 characters, 32767 bytes). */
    fun sendMessage(channel: String, data: ByteArray)

    /**
     * Sends a raw play-state packet: its 1.8.9 serverbound id (0x00 keep-alive .. 0x19 resource pack status) and
     * its body (without the length and id). The arrays passed to this and [sendMessage] are copied at once.
     */
    fun sendPacket(id: Int, body: ByteArray)
}

enum class GameMode { SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR }

enum class Dimension { OVERWORLD, NETHER, END }

/** The local player at one moment. */
data class PlayerState(
    val name: String,
    val uuid: UUID,
    val entityId: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    /** Blocks per tick. */
    val motionX: Double,
    val motionY: Double,
    val motionZ: Double,
    /** Minecraft's yaw (0 looks south, 90 west) and pitch (positive looks down), in degrees. */
    val yaw: Float,
    val pitch: Float,
    val onGround: Boolean,
    val sneaking: Boolean,
    val sprinting: Boolean,
    /** Half-hearts (20 is full). */
    val health: Float,
    val maxHealth: Float,
    val absorption: Float,
    /** Half-drumsticks (20 is full). */
    val food: Int,
    val saturation: Float,
    /** 300 is a full breath. */
    val air: Int,
    val level: Int,
    /** Progress to the next level, 0..1. */
    val experience: Float,
    val gameMode: GameMode,
    val dimension: Dimension,
    /** 0..8. */
    val selectedSlot: Int,
    val effects: List<Effect>,
    val riding: Boolean,
    val usingItem: Boolean,
)

/** A potion effect; [id] is 1.8.9's (1 speed .. 23 saturation), [amplifier] 0 for level I. */
data class Effect(val id: Int, val name: String, val amplifier: Int, val ticksLeft: Int)

/** An item stack. [id] is the numeric 1.8.9 id; [name] its registry name, such as `diamond_sword`. */
data class Item(
    val id: Int,
    val name: String,
    val count: Int,
    val damage: Int,
    /** An anvil name, without formatting codes. */
    val customName: String?,
    /** Enchantment level by 1.8.9 enchantment id (16 sharpness, 0 protection...). */
    val enchantments: Map<Int, Int>,
)

/** The player's own inventory. */
data class InventoryState(
    val selectedSlot: Int,
    /** 9 slots, left to right. */
    val hotbar: List<Item?>,
    /** The 27 slots above the hotbar, top left first. */
    val main: List<Item?>,
    /** Helmet, chestplate, leggings, boots. */
    val armor: List<Item?>,
    /** What the pointer holds in an open screen. */
    val cursor: Item?,
) {
    val held: Item? get() = hotbar[selectedSlot]
}

enum class EntityKind { PLAYER, MOB, OBJECT }

data class EntityState(
    val id: Int,
    val kind: EntityKind,
    /** `player`, a mob's name (`zombie`, `cave_spider`...) or an object's (`arrow`, `item`, `boat`...). */
    val type: String,
    /** A player's name (from the player list), or a custom name tag; null otherwise. */
    val name: String?,
    val uuid: UUID?,
    val x: Double,
    val y: Double,
    val z: Double,
    val motionX: Double,
    val motionY: Double,
    val motionZ: Double,
    val yaw: Float,
    val pitch: Float,
    val headYaw: Float,
    val onGround: Boolean,
    val sneaking: Boolean,
    val sprinting: Boolean,
    val invisible: Boolean,
    /** Living entities' health as the server reports it; null for objects. */
    val health: Float?,
    /** Held item, boots, leggings, chestplate, helmet. */
    val equipment: List<Item?>,
    /** Hurt animation ticks left (10 just hit, 0 not hurt). */
    val hurtTime: Int,
)

/** A block state: the numeric 1.8.9 block id, its metadata (0..15), and its registry name (`stone`, `air`...). */
data class Block(val id: Int, val meta: Int, val name: String) {
    val air: Boolean get() = id == 0
}

enum class Face { DOWN, UP, NORTH, SOUTH, WEST, EAST }

sealed interface Target {
    data class BlockHit(val x: Int, val y: Int, val z: Int, val face: Face, val block: Block) : Target
    data class EntityHit(val entity: EntityState) : Target
}

/** The world the player is in. Render thread, like the rest of the game's state. */
interface World {
    val dimension: Dimension
    /** Ticks since the world was made, and the day's time (0 sunrise, 6000 noon, 18000 midnight). */
    val worldAge: Long
    val timeOfDay: Long
    /** 0..1. */
    val rain: Float
    val thunder: Float

    /** The block at a position; air where nothing is loaded. */
    fun block(x: Int, y: Int, z: Int): Block

    /** Changes a block on the client only (the server's next update of it wins). Any thread. */
    fun setBlock(x: Int, y: Int, z: Int, id: Int, meta: Int = 0)

    /** Whether the chunk holding block column ([x], [z]) has arrived. */
    fun loaded(x: Int, z: Int): Boolean

    /** The 1.8.9 biome id at a column. */
    fun biome(x: Int, z: Int): Int

    /** Sky light and block light, 0..15 each. */
    fun skyLight(x: Int, y: Int, z: Int): Int
    fun blockLight(x: Int, y: Int, z: Int): Int
}

data class ListedPlayer(
    val uuid: UUID,
    val name: String,
    /** The name the server shows, with § formatting codes. */
    val displayName: String,
    val ping: Int,
    val gameMode: GameMode,
)

data class Sidebar(val title: String, val lines: List<SidebarLine>)

data class SidebarLine(val text: String, val score: Int)

data class ServerState(
    /** As typed. */
    val address: String,
    /** The server software's name from MC|Brand. */
    val brand: String,
    /** The player list, in the tab overlay's order. */
    val players: List<ListedPlayer>,
    val playerListHeader: String,
    val playerListFooter: String,
    val sidebar: Sidebar?,
    val title: String,
    val subtitle: String,
    val actionBar: String,
)

/** A point in the game view: fractions of its width and height from the top left (0..1 when on screen). */
data class ScreenPoint(val x: Float, val y: Float, /** Distance from the camera, in blocks. */ val distance: Float)

/** A chat message on its way to the chat; [text] has § formatting codes, [plain] has none. */
class ChatMessage(val text: String) {
    val plain: String = text.replace(Regex("§."), "")

    var hidden: Boolean = false
        private set

    /** The text that will be shown instead, if a listener replaced it. */
    var replacement: String? = null
        private set

    /** Leaves the message out of the chat. */
    fun hide() {
        hidden = true
    }

    /** Shows [text] (§ codes allowed) instead; its click and hover actions are dropped. */
    fun replace(text: String) {
        replacement = text
    }
}

/** A command the player typed: `/name arg arg`. */
class CommandCall(val name: String, val args: List<String>, val line: String, private val reply: (String) -> Unit) {
    /** Answers in the player's own chat, under the plugin's name. */
    fun reply(text: String) = reply.invoke(text)
}

/** One press of the attack or use binding in game. */
class Click(val button: Button, /** System.currentTimeMillis() of the press. */ val timeMillis: Long) {
    enum class Button { ATTACK, USE }

    var cancelled: Boolean = false
        private set

    /** The game ignores this press, as if it never happened. */
    fun cancel() {
        cancelled = true
    }
}

/** One key press while playing: its binding name (`key:W`, `key:Left Shift`...). */
class KeyPress(val binding: String, val timeMillis: Long) {
    var cancelled: Boolean = false
        private set

    /** The game ignores this press (the key also does not count as held). */
    fun cancel() {
        cancelled = true
    }
}

/** One tick's movement input: forward and strafe are -1..1 (1 forward, 1 left). */
class Movement(
    var forward: Float,
    var strafe: Float,
    var jump: Boolean,
    var sneak: Boolean,
    var sprint: Boolean,
)
