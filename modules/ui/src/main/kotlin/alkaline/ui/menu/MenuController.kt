package alkaline.ui.menu

import alkaline.client.ClientServices
import alkaline.config.ConfigSnapshot
import alkaline.config.ConfigStore
import alkaline.config.ProfileStore
import alkaline.config.Setting
import alkaline.ui.hud.ChatInput
import alkaline.ui.hud.HudEditor
import alkaline.ui.hud.HudSprites
import alkaline.ui.hud.HudState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

enum class MenuPage { TITLE, SINGLEPLAYER, MULTIPLAYER, SETTINGS }

/** Whether the player is in the menus, joining a server, playing, or paused in game. */
enum class PlayPhase { MENU, CONNECTING, PLAYING, PAUSED }

/**
 * What covers the game while playing: nothing, the ClickGUI, the HUD editor, a container screen (the inventory
 * or a window the server opened), the sign editor, the chat line being typed, or a server's resource pack offer.
 */
enum class Overlay { NONE, CLICK_GUI, HUD_EDITOR, INVENTORY, SIGN, CHAT, SERVER_PACK }

/**
 * A container screen's input: pointer events by window slot ({@code -999} outside the window, -1 inside it but
 * not on a slot), and the buttons some windows have. The client applies vanilla's GuiContainer rules on its own
 * thread, where the real cursor stack lives.
 */
interface InventoryInput {
    fun hover(slot: Int)
    /** [button]: 0 left, 1 right, 2 middle. */
    fun press(slot: Int, button: Int)
    /** With a button held, the pointer entered [slot]. */
    fun drag(slot: Int)
    fun release(slot: Int, button: Int)
    /** The enchanting table's option 0..2. */
    fun enchant(option: Int) {}
    /** The villager trade to show. */
    fun selectTrade(index: Int) {}
    /** The anvil's name field changed. */
    fun rename(name: String) {}
    /** The beacon's confirm, with the chosen effect ids. */
    fun beacon(primary: Int, secondary: Int) {}
}

/** Where the chat sends what the player types; the client answers tab completion through [MenuController.chat]. */
interface ChatHost {
    /** The player sent this chat line or command. */
    fun send(message: String)

    /** Asks the server to tab-complete [text]. */
    fun complete(text: String)

    /** Puts [text] on the clipboard. */
    fun copy(text: String)
}

/** The ClickGUI's sidebar sections (the HUD editor sits at the bottom of the sidebar and opens over the game). */
enum class ClickGuiSection { MODULES, PLUGINS, MUSIC }

/** Startup sequence: the loading screen, then the background alone, then the menu animating in. */
enum class MenuPhase { LOADING, REVEAL, READY }

/** What the menu asks of its host. Called on the UI thread; implementations must hand work off quickly. */
interface MenuActions {
    fun quit()

    /** Relaunches the client with the same arguments after a clean shutdown; ignored when [canRestart] is false. */
    fun restart() = quit()

    /** Whether [restart] can relaunch the process here, rather than only quitting. */
    val canRestart: Boolean get() = false

    /** Shown at the bottom of the settings rail, for example "Alkaline 0.1.0-dev". */
    val versionLabel: String

    /** Opens a web page in the player's browser (Microsoft sign-in). */
    fun openUrl(url: String) {}

    /** Connects to a multiplayer server; the host reports back through [MenuController.joined] or [MenuController.left]. */
    fun join(address: String) {}

    /** Returns from the pause card to the game (recaptures the mouse). */
    fun resumeGame() {}

    /** Disconnects from the server, or cancels a connection in progress. */
    fun leaveGame() {}

    /** The player closed the container screen. */
    fun inventoryClosed() {}

    /** The sign editor closed with these four lines. */
    fun signDone(lines: List<String>) {}

    /** Lists the resource packs in the pack folders; the host answers through [MenuController.resourcePacks]. */
    fun scanResourcePacks() {}

    /** Opens Alkaline's resource pack folder in the system file manager. */
    fun openResourcePacksFolder() {}

    /** Reloads the assets with the packs the settings select; progress arrives in [MenuController.resourcePackStatus]. */
    fun applyResourcePacks() {}
}

/** A resource pack the host found, for the pack list. */
data class ResourcePackInfo(
    val id: String,
    val name: String,
    val description: String,
    val icon: alkaline.renderer.image.RgbaImage?,
    /** Made for 1.8.9's pack format; others still load, possibly with missing or misplaced textures. */
    val compatible: Boolean,
)

/**
 * Menu navigation state, hoisted outside the composition so it survives the host recreating its scene on
 * resize. Back navigation from the keyboard goes through [back].
 */
class MenuController @JvmOverloads constructor(
    val config: ConfigStore,
    val actions: MenuActions,
    val services: ClientServices,
    startLoading: Boolean = false,
    /** Module and HUD settings by profile; null where modules do not apply (previews). */
    val profiles: ProfileStore? = null,
) {
    /** The HUD's values, replaced by the client on the UI thread. */
    val hud = HudState()

    /** The HUD editor's selection and snapping. */
    val hudEditor = HudEditor()

    /** Refresh rate of the window's display in whole Hz, 0 when unknown; the client keeps it current. */
    var displayRate by mutableStateOf(0)

    /** The game's sprites for the HUD, once the client has opened its assets. */
    var hudSprites by mutableStateOf<HudSprites?>(null)

    /** Packs found by the last [MenuActions.scanResourcePacks]. */
    var resourcePacks by mutableStateOf<List<ResourcePackInfo>>(emptyList())

    /** The selection the loaded assets were built from, so the pack list knows whether to offer Apply. */
    var appliedResourcePacks by mutableStateOf("")

    /** A reload in progress or its failure; empty when the assets match [appliedResourcePacks]. */
    var resourcePackStatus by mutableStateOf("")

    var overlay by mutableStateOf(Overlay.NONE)
        private set

    var clickGuiSection by mutableStateOf(ClickGuiSection.MODULES)

    /** Profile names and the active one, as Compose state (the store changes them on the UI thread). */
    var profileNames by mutableStateOf(profiles?.names() ?: emptyList())
        private set
    var activeProfile by mutableStateOf(profiles?.active() ?: "")
        private set

    init {
        profiles?.addListener {
            profileNames = profiles.names()
            activeProfile = profiles.active()
        }
    }

    /** Opens or closes the ClickGUI in game (its key, or the pause card). Call on the UI thread. */
    fun toggleClickGui() {
        when {
            overlay == Overlay.INVENTORY || overlay == Overlay.SIGN || overlay == Overlay.CHAT ||
                overlay == Overlay.SERVER_PACK -> {}
            overlay != Overlay.NONE -> closeOverlay()
            profiles != null && (play == PlayPhase.PLAYING || play == PlayPhase.PAUSED) -> overlay = Overlay.CLICK_GUI
        }
    }

    /** Where the inventory screen sends pointer input; set by the client. */
    var inventoryInput: InventoryInput? = null

    /** Opens the inventory over the game (its key while playing). Call on the UI thread. */
    fun openInventory() {
        if (play == PlayPhase.PLAYING && overlay == Overlay.NONE) overlay = Overlay.INVENTORY
    }

    /**
     * The server opened a window (a chest, a furnace, a villager...): its screen covers the game, replacing the
     * pause card or any other screen. Call on the UI thread.
     */
    fun openWindow() {
        if (play != PlayPhase.PLAYING && play != PlayPhase.PAUSED) return
        if (page == MenuPage.SETTINGS) page = MenuPage.TITLE
        play = PlayPhase.PLAYING
        overlay = Overlay.INVENTORY
    }

    /** The server closed the window: leave the screen without reporting a close back. */
    fun inventoryClosedByServer() {
        if (overlay == Overlay.INVENTORY) {
            overlay = Overlay.NONE
            if (play == PlayPhase.PLAYING) actions.resumeGame()
        }
    }

    /** Where chat goes; set by the client. */
    var chatHost: ChatHost? = null

    /** The chat line being typed, while [Overlay.CHAT] is open. */
    val chat = ChatInput(object : ChatInput.Host {
        override fun send(message: String) { chatHost?.send(message) }
        override fun complete(text: String) { chatHost?.complete(text) }
        override fun openUrl(url: String) = actions.openUrl(url)
        override fun copy(text: String) { chatHost?.copy(text) }
        override fun close() = closeOverlay()
    })

    /** Opens the chat over the game with [initial] typed ("/" for commands). Call on the UI thread. */
    fun openChat(initial: String = "") {
        if (play != PlayPhase.PLAYING || overlay != Overlay.NONE) return
        chat.open(initial)
        typing = true
        overlay = Overlay.CHAT
    }

    /** A server's resource pack offer waiting for the player: the server's name and where the answer goes. */
    class ServerPackOffer(val server: String, internal val answer: (Boolean) -> Unit)

    var serverPackOffer by mutableStateOf<ServerPackOffer?>(null)
        private set

    /** The server pack being fetched or applied: null when none, a negative value while its size is unknown. */
    var serverPackProgress by mutableStateOf<Float?>(null)

    /** What the server pack is doing, shown beside [serverPackProgress]. */
    var serverPackStatus by mutableStateOf("")

    /**
     * The server offered a resource pack and the player has not chosen for this server (GuiYesNo in
     * NetHandlerPlayClient.handleResourcePack). Escape declines, as No does.
     */
    fun offerServerPack(server: String, answer: (Boolean) -> Unit) {
        serverPackOffer?.answer?.invoke(false)
        closeOverlay()
        serverPackOffer = ServerPackOffer(server, answer)
        overlay = Overlay.SERVER_PACK
    }

    /** The player answered the offer. */
    fun answerServerPack(use: Boolean) {
        val offer = serverPackOffer ?: return
        serverPackOffer = null
        if (overlay == Overlay.SERVER_PACK) {
            overlay = Overlay.NONE
            if (play == PlayPhase.PLAYING) actions.resumeGame()
        }
        offer.answer(use)
    }

    /** The sign editor's four lines while it is open. */
    var signLines by mutableStateOf(listOf("", "", "", ""))

    /** The server asked for a sign to be written; the editor starts from its current [lines]. */
    fun openSign(lines: List<String>) {
        if (play != PlayPhase.PLAYING && play != PlayPhase.PAUSED) return
        if (overlay == Overlay.INVENTORY) actions.inventoryClosed()
        signLines = List(4) { lines.getOrElse(it) { "" } }
        play = PlayPhase.PLAYING
        overlay = Overlay.SIGN
    }

    /**
     * Whether a text field in game (the anvil's name, the sign editor) has the keyboard: game keys, the inventory
     * key included, then type instead of acting. The client reads it on its own thread.
     */
    @Volatile
    var typing = false

    /** Leaves the ClickGUI for the HUD editor over the game. */
    fun openHudEditor() {
        if (overlay != Overlay.NONE) {
            hudEditor.selected = null
            overlay = Overlay.HUD_EDITOR
        }
    }

    /** Back from the HUD editor to the ClickGUI. */
    fun closeHudEditor() {
        if (overlay == Overlay.HUD_EDITOR) overlay = Overlay.CLICK_GUI
    }

    fun closeOverlay() {
        if (overlay == Overlay.NONE) return
        if (overlay == Overlay.INVENTORY) actions.inventoryClosed()
        if (overlay == Overlay.SIGN) actions.signDone(signLines)
        if (overlay == Overlay.CHAT) chat.close()
        if (overlay == Overlay.SERVER_PACK) {
            answerServerPack(false)
            return
        }
        typing = false
        overlay = Overlay.NONE
        if (play == PlayPhase.PLAYING) actions.resumeGame()
    }

    /** Whether the client should hold the mouse for the game (playing, nothing open over it). */
    fun gameHasInput(): Boolean = play == PlayPhase.PLAYING && overlay == Overlay.NONE

    /** Runs a profile operation, reporting failures (invalid or taken names) as the returned message. */
    fun profileAction(action: (ProfileStore) -> Unit): String? {
        val store = profiles ?: return "Profiles are unavailable."
        return try {
            action(store)
            null
        } catch (failure: IllegalArgumentException) {
            failure.message
        } catch (failure: java.io.IOException) {
            "Could not save the profile: ${failure.message}"
        }
    }

    var phase by mutableStateOf(if (startLoading) MenuPhase.LOADING else MenuPhase.READY)
        private set

    /** One short line under the loading indicator, for example "Loading vanilla assets". */
    var loadingStatus by mutableStateOf("")

    /** Ends the loading screen; safe to call from any thread, and more than once. */
    fun finishLoading() {
        if (phase == MenuPhase.LOADING) phase = MenuPhase.REVEAL
    }

    internal fun revealed() {
        phase = MenuPhase.READY
    }

    /** Values the running client started with; restart-only settings compare against these. */
    val launchSettings: ConfigSnapshot = config.snapshot()

    var page by mutableStateOf(MenuPage.TITLE)
        private set
    var settingsCategory by mutableStateOf(config.schema().categories().keys.first())

    fun open(target: MenuPage) {
        page = target
    }

    var play by mutableStateOf(PlayPhase.MENU)
        private set

    /** The server being joined or played on. */
    var playAddress by mutableStateOf("")
        private set

    /** Why the last session ended (a server's kick message or a connection error); null after a clean leave. */
    var playError by mutableStateOf<String?>(null)
        private set

    fun join(address: String) {
        playAddress = address
        playError = null
        play = PlayPhase.CONNECTING
        actions.join(address)
    }

    /** The host finished logging in; the world takes over the screen. Call on the UI thread. */
    fun joined() {
        if (play == PlayPhase.CONNECTING) play = PlayPhase.PLAYING
    }

    /** Opens the pause card (the host releases the mouse). Call on the UI thread. */
    fun pause() {
        if (play == PlayPhase.PLAYING) play = PlayPhase.PAUSED
    }

    fun resume() {
        if (play == PlayPhase.PAUSED) {
            play = PlayPhase.PLAYING
            actions.resumeGame()
        }
    }

    /** The player chose to leave or cancel. */
    fun leave() {
        actions.leaveGame()
    }

    /** The session ended; back to the server list, with [reason] shown when it was not the player's choice. */
    fun left(reason: String?) {
        if (overlay == Overlay.INVENTORY) actions.inventoryClosed()
        chat.forget()
        serverPackOffer = null
        serverPackProgress = null
        typing = false
        overlay = Overlay.NONE
        play = PlayPhase.MENU
        playError = reason
        page = MenuPage.MULTIPLAYER
    }

    /** Id of the key binding waiting for its next input, as Compose state for the Controls page. */
    var capturingBinding by mutableStateOf<String?>(null)
        private set
    @Volatile private var capturing: Setting.KeySetting? = null
    private var capturingStore: ConfigStore = config

    /**
     * Whether a binding is waiting for input. The host reads this on its own thread for every key and mouse
     * button press: while true, the press belongs to the binding, not to navigation or the UI.
     */
    fun isCapturingBinding(): Boolean = capturing != null

    /** Listens for the next input for [setting] in [store] (client settings, or the active profile's modules). */
    internal fun startCapture(store: ConfigStore, setting: Setting.KeySetting) {
        capturingStore = store
        capturing = setting
        capturingBinding = setting.id()
    }

    /**
     * Ends a capture; call on the UI thread. [binding] is the input pressed (see [Setting.KeySetting]); null
     * cancels and keeps the old binding.
     */
    fun finishCapture(binding: String?) {
        val setting = capturing ?: return
        capturing = null
        capturingBinding = null
        if (binding != null) capturingStore.update(setting, binding)
    }

    /** Returns false when already on the title page. */
    fun back(): Boolean {
        if (capturing != null) {
            finishCapture(null)
            return true
        }
        if (overlay == Overlay.CHAT && chat.pendingUrl != null) {
            chat.dismissPending()
            return true
        }
        when (overlay) {
            Overlay.HUD_EDITOR -> {
                closeHudEditor()
                return true
            }
            Overlay.CLICK_GUI, Overlay.INVENTORY, Overlay.SIGN, Overlay.CHAT, Overlay.SERVER_PACK -> {
                closeOverlay()
                return true
            }
            Overlay.NONE -> {}
        }
        when (play) {
            PlayPhase.PLAYING -> {
                pause()
                return true
            }
            PlayPhase.PAUSED -> {
                if (page == MenuPage.SETTINGS) {
                    page = MenuPage.TITLE
                } else {
                    resume()
                }
                return true
            }
            PlayPhase.CONNECTING -> {
                leave()
                return true
            }
            PlayPhase.MENU -> {}
        }
        if (page == MenuPage.TITLE) return false
        page = MenuPage.TITLE
        return true
    }
}

/** Restart-only settings whose saved value differs from the one this client launched with. */
fun MenuController.pendingRestart(current: ConfigSnapshot): List<Setting<*>> =
    config.schema().settings().filter { it.restartRequired() && current.value(it) != launchSettings.value(it) }

/** Observes the config store's save state as Compose state. */
@Composable
fun rememberSaveState(store: ConfigStore): State<ConfigStore.SaveState> {
    val state = remember(store) { mutableStateOf(store.saveState()) }
    DisposableEffect(store) {
        val listener = ConfigStore.SaveListener { state.value = it }
        store.addSaveListener(listener)
        state.value = store.saveState()
        onDispose { store.removeSaveListener(listener) }
    }
    return state
}

/** Observes the config store as Compose state; writes go straight to the store. */
@Composable
fun rememberConfig(store: ConfigStore): State<ConfigSnapshot> {
    val state = remember(store) { mutableStateOf(store.snapshot()) }
    DisposableEffect(store) {
        val listener = ConfigStore.Listener { _, current -> state.value = current }
        store.addListener(listener)
        state.value = store.snapshot()
        onDispose { store.removeListener(listener) }
    }
    return state
}

internal fun <T> ConfigStore.update(setting: Setting<T>, value: T) {
    runCatching { set(setting, value) }.onFailure { System.err.println("Rejected ${setting.id()}: ${it.message}") }
}
