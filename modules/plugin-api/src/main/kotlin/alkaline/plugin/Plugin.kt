package alkaline.plugin

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * A plugin's entry point: the class its manifest's `entrypoint` names, with a public no-argument constructor.
 *
 * A plugin is a jar with `alkaline-plugin.json` at its root (the manifest; see `docs/plugins/manifest.md`). The
 * manifest says who the plugin is and declares what Alkaline must know before loading any of its code: its
 * settings, its HUD elements, and the plugins it needs.
 *
 * Plugins installed by the player are unrestricted: they run in the client's process with the player's own access to
 * the computer, and every part of this API is open to them. Install only plugins you trust.
 */
interface AlkalinePlugin {
    /** The player turned the plugin on (or left it on last time). UI thread. */
    fun enable(context: PluginContext)

    /**
     * The plugin is being turned off (UI thread) or the client is closing (its closing thread). Its registrations
     * are already removed and its [PluginContext.scope] cancelled.
     */
    fun disable() {}
}

/**
 * Compatibility: within one API version, Alkaline only adds to this API. Classes it hands to plugins (the game's
 * state snapshots such as [PlayerState], and the event objects) may gain properties, and enums and sealed types
 * may gain values: read properties by name, give a `when` over an enum an `else` branch, and do not construct
 * snapshots or call their `copy` or `componentN` functions. The client checks the API's binary interface on
 * every build against `modules/plugin-api/api/`, so a change that would break built plugins cannot slip in.
 *
 * Only `alkaline.plugin` is the API. A plugin can reach the client's other classes (it shares the class path), but
 * they change without notice.
 *
 * The API version this client implements. Additions keep the number; a removal or an incompatible change raises
 * it. A manifest's `api` names the version the plugin was built against; the client loads `api` from
 * [PLUGIN_API_MINIMUM] through this version.
 */
const val PLUGIN_API_VERSION = 1

/** The oldest API version this client still loads. */
const val PLUGIN_API_MINIMUM = 1

/** What the plugin's manifest says about it. */
data class PluginInfo(
    val id: String,
    val name: String,
    /** A Semantic Versioning 2.0 version, such as `1.4.0` or `2.0.0-beta.1`. */
    val version: String,
    val description: String,
    val authors: List<Author>,
    /** An SPDX license expression, such as `MIT`, or null when the manifest names none. */
    val license: String?,
    val homepage: String?,
    val source: String?,
    val issues: String?,
    /** The `api` the plugin was built against. */
    val api: Int,
)

data class Author(val name: String, val contact: String? = null)

/** Undoes a registration. Removing twice is harmless. */
fun interface Registration {
    fun remove()
}

enum class NotificationKind { INFO, SUCCESS, WARNING }

/**
 * Everything Alkaline offers one enabled plugin. Registrations last until they are removed or the plugin is
 * disabled, whichever comes first; the plugin never has to undo them itself.
 *
 * A context belongs to one run of the plugin: from `enable` until it is turned off or stops after an error. After
 * that the context is inactive ([active] is false): registering throws [IllegalStateException], and actions
 * (notifications, chat, input, sounds, packets) do nothing. A plugin that is turned on again gets a new context.
 *
 * Unless a member says otherwise, it may be called from any thread.
 */
interface PluginContext {
    val info: PluginInfo

    /** Whether this run of the plugin is still on. Long work in [scope] or on threads of its own may check it. */
    val active: Boolean

    /** A folder of the plugin's own for data; created on first use, kept when the plugin is updated. */
    val dataFolder: Path

    /** Lines in the client's log under the plugin's id, also shown on its card in the Plugins section. */
    val log: PluginLog

    /** The settings the manifest declares, as the player set them. */
    val settings: PluginSettings

    /** Coroutines for the plugin's own work, on background threads; cancelled when the plugin is disabled. */
    val scope: CoroutineScope

    /** Runs [task] on the UI thread (where Compose state is safest to change), unless the plugin stops first. */
    fun runOnUiThread(task: () -> Unit)

    /**
     * Shows a notification under the plugin's name. The same notification again within a few seconds is merged
     * into the one showing, and when a plugin sends many at once only the first few show; the rest go to its log.
     */
    fun notify(title: String, text: String = "", kind: NotificationKind = NotificationKind.INFO)

    /**
     * Supplies the content of the HUD element the manifest declares as [element]. The player places, scales and
     * hides it in the HUD editor like Alkaline's own; [content] composes on the UI thread inside Alkaline's theme.
     */
    fun hud(element: String, content: @Composable () -> Unit): Registration

    /**
     * Draws [content] over the whole game view while playing, under the HUD and without input: markers, labels
     * placed with [Game.project], screen tints.
     */
    fun overlay(content: @Composable () -> Unit): Registration

    /** Shows [content] on the plugin's card in the ClickGUI's Plugins section, under its settings. */
    fun page(content: @Composable () -> Unit): Registration

    /**
     * Opens a screen of the plugin's in game, in Alkaline's panel under [title], over the game with the mouse free.
     * Escape or `close` closes it. Does nothing outside a game. Any thread.
     */
    fun openScreen(title: String, content: @Composable (close: () -> Unit) -> Unit)

    /** Closes the plugin's screen when it is open. Any thread. */
    fun closeScreen()

    /**
     * The running instance of the plugin [id], when it is one of this plugin's dependencies (or optional ones)
     * and an instance of [type]; null otherwise. A plugin sees its dependencies' classes, so it can call them.
     */
    fun <T : Any> dependency(id: String, type: Class<T>): T?

    /** The game: its state, its events, and control over it. */
    val game: Game

    /** Alkaline's music player: what it plays, and control over it. */
    val music: Music
}

/** A track as the music player knows it. */
data class MusicTrack(
    val title: String,
    val artist: String?,
    val album: String?,
    /** Null for a live stream. */
    val durationMillis: Long?,
    /** A file path or the link it was added from. */
    val location: String,
)

/**
 * The music player (the ClickGUI's Music section). Any thread; controls are queued for the player and return at
 * once. Without a player (a build without one) [available] is false, reads are empty and controls do nothing.
 */
interface Music {
    val available: Boolean

    /** The track being heard (or paused), or null. */
    val track: MusicTrack?
    val playing: Boolean
    val positionMillis: Long

    /** The volume slider's position, 0..1 (the gain is its cube). */
    var volume: Float

    /**
     * Plays [input] now: a file or folder path, a link (YouTube, SoundCloud, Spotify, Apple Music, a stream...) or,
     * otherwise, the first YouTube result for it. Finding it runs in the background; [onTrack] tells when it plays.
     */
    fun play(input: String)

    /** Adds [input] (as for [play]) at the end of the queue. */
    fun enqueue(input: String)

    fun pause()
    fun resume()
    fun toggle()
    fun next()
    fun previous()
    fun seek(millis: Long)

    /** Each change of the track being heard (null when playback stops), on the UI thread. */
    fun onTrack(listener: (MusicTrack?) -> Unit): Registration
}

inline fun <reified T : Any> PluginContext.dependency(id: String): T? = dependency(id, T::class.java)

interface PluginLog {
    fun info(message: String)
    fun warn(message: String, failure: Throwable? = null)
    fun error(message: String, failure: Throwable? = null)
}

/**
 * The settings in the manifest's `settings`, by id. Asking for an undeclared id, or with the wrong type, throws
 * [IllegalArgumentException].
 */
interface PluginSettings {
    fun boolean(id: String): PluginSetting<Boolean>
    fun integer(id: String): PluginSetting<Int>
    fun decimal(id: String): PluginSetting<Float>
    /** 32-bit ARGB. */
    fun color(id: String): PluginSetting<Int>
    fun text(id: String): PluginSetting<String>
    /** One of the setting's declared `options`. */
    fun choice(id: String): PluginSetting<String>
    /** A binding: `none`, `key:<key name>` (such as `key:R`), or `mouse:<left|right|middle|x1|x2>`. */
    fun key(id: String): PluginSetting<String>
}

interface PluginSetting<T> {
    val id: String

    /** The current value. Any thread; read in a composable, the composable recomposes when it changes. */
    val value: T

    /** Saves [value]; throws [IllegalArgumentException] outside the declared range or options. Any thread. */
    fun set(value: T)

    fun reset()

    /** Runs [listener] with each new value, on the thread that changed it (usually the UI thread). */
    fun onChange(listener: (T) -> Unit): Registration
}
