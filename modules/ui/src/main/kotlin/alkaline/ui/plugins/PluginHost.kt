package alkaline.ui.plugins

import alkaline.config.ConfigSchema
import alkaline.config.ConfigSnapshot
import alkaline.config.ConfigStore
import alkaline.config.Json
import alkaline.config.Setting
import alkaline.plugin.AlkalinePlugin
import alkaline.plugin.ChatMessage
import alkaline.plugin.Click
import alkaline.plugin.CommandCall
import alkaline.plugin.EntityState
import alkaline.plugin.Game
import alkaline.plugin.InventoryState
import alkaline.plugin.KeyPress
import alkaline.plugin.Movement
import alkaline.plugin.NotificationKind
import alkaline.plugin.PlayerState
import alkaline.plugin.PluginContext
import alkaline.plugin.PluginInfo
import alkaline.plugin.PluginLog
import alkaline.plugin.PluginSetting
import alkaline.plugin.PluginSettings
import alkaline.plugin.Registration
import alkaline.plugin.ScreenPoint
import alkaline.plugin.ServerState
import alkaline.plugin.Target
import alkaline.plugin.World
import alkaline.ui.hud.HudContribution
import alkaline.ui.hud.Notification
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

/**
 * Loads plugin jars from [folder], remembers which the player turned on (`plugins.json` there), and connects each
 * running plugin to the client through typed hooks: one list per kind of hook, each entry owned by a plugin, so
 * stopping a plugin removes exactly what it added. There is no general event bus. Plugins are unrestricted: every
 * hook is open to every plugin the player installed.
 *
 * A plugin's dependencies start before it and stop after it; its class loader sees their classes. Enabling a
 * plugin enables what it needs, and disabling one disables what needs it.
 *
 * Enabling, disabling and the interface contributions run on the UI thread; the game hooks ([tick], [chat],
 * [click] and the rest) run on the client's render thread. [post] moves work to the UI thread.
 *
 * The host contains plugin faults without limiting what plugins may do:
 * - Every hook belongs to one run of a plugin (its [Context]). A plugin that throws is quarantined at once, so
 *   its other hooks stop firing before the UI thread gets round to stopping it, and a late failure from an old run
 *   never stops a restarted one.
 * - A [watchdog] reports a plugin that holds a thread for seconds, and the time each plugin spends in hooks is
 *   counted ([usage]).
 * - Jars load from copies, so a jar can be replaced (and reloaded) while the client runs, on Windows too.
 * - Logs and notifications are flood-controlled.
 * - [recover] finds the plugin behind an interface failure, so the menu can stop it and rebuild its scene.
 */
class PluginHost(
    val folder: Path,
    private val post: (() -> Unit) -> Unit,
    private val notify: (Notification) -> Unit,
) : AutoCloseable {
    /**
     * What the running game offers plugins; implemented by the client. Reads and actions are called on the render
     * thread (the host checks or queues), except where noted.
     */
    interface GameBridge {
        /** Any thread. */
        val inGame: Boolean
        /** Any thread. */
        fun onRenderThread(): Boolean
        /** Queues [task] for the render thread. Any thread. */
        fun runOnRenderThread(task: Runnable)
        fun player(): PlayerState?
        fun inventory(): InventoryState?
        fun world(): World?
        fun entities(): List<EntityState>
        fun entity(id: Int): EntityState?
        fun target(): Target?
        fun server(): ServerState?
        /** Any thread. */
        fun project(x: Double, y: Double, z: Double): ScreenPoint?
        fun sendChat(message: String)
        fun showChat(text: String)
        fun attack()
        fun use()
        fun select(slot: Int)
        fun look(yaw: Float, pitch: Float)
        /** Any thread. */
        fun playSound(sound: String, volume: Float, pitch: Float)
        /** Any thread. */
        fun playSound(sound: String, x: Double, y: Double, z: Double, volume: Float, pitch: Float)
        fun sendMessage(channel: String, data: ByteArray)
        fun sendPacket(id: Int, body: ByteArray)
    }

    @Volatile
    var game: GameBridge? = null

    /** The music player, once the client has one. UI thread. */
    var music: alkaline.ui.music.MusicController? = null
        set(value) {
            field = value
            value?.onTrack = { track ->
                val shown = track?.let(::musicTrack)
                for (hook in musicTracks) guard(hook.owner, "music.onTrack") { hook.value(shown) }
            }
        }

    private fun musicTrack(track: alkaline.music.Track) =
        alkaline.plugin.MusicTrack(track.title, track.artist, track.album, track.durationMillis, track.location)

    /** Opens (or, with null, closes) a plugin's screen; set by the menu. UI thread. */
    var screens: (PluginScreen?) -> Unit = {}

    /** A jar in the plugin folder: its manifest, or why it cannot load, and its state. UI-thread state. */
    class Installed internal constructor(val jar: Path, val manifest: Manifest?, problems: List<String>, val icon: ByteArray?) {
        /** The jar's size and modification time when its manifest was read. */
        internal val stamp: Pair<Long, Long>? = stampOf(jar)
        var enabled by mutableStateOf(false)
            internal set
        /** Why the plugin cannot run (a bad manifest, a missing dependency, the error that stopped it). */
        var problems by mutableStateOf(problems)
            internal set
        /** The latest lines the plugin logged, oldest first. */
        val log = mutableStateListOf<String>()
        /** Its declared settings and HUD placements; null when it declares none or its manifest is bad. */
        var store: ConfigStore? = null
            internal set
        internal var snapshot: androidx.compose.runtime.MutableState<ConfigSnapshot>? = null
        internal var running: Running? = null
        internal var wantsEnabled = false
        val id: String get() = manifest?.id ?: jar.fileName.toString()
        val name: String get() = manifest?.name ?: jar.fileName.toString()

        /** The settings snapshot as Compose state, for the plugin's card. */
        val settings: State<ConfigSnapshot>? get() = snapshot
    }

    internal class Running(val loader: PluginClassLoader, val copy: Path, val plugin: AlkalinePlugin, val context: Context)

    /** Time a running plugin has spent in its hooks: totals since it started. */
    class Usage(val nanos: Long, val calls: Long, val slowestNanos: Long)

    /** A plugin's open screen. */
    class PluginScreen internal constructor(val owner: String, val title: String,
                                            val content: @Composable (close: () -> Unit) -> Unit)

    /** One hook entry and the plugin that registered it. */
    private class Hook<T>(val owner: Context, val value: T)

    private class Scheduled(val owner: Context, var ticks: Int, val task: () -> Unit)

    private class Command(val owner: Context, val description: String, val handler: (CommandCall) -> Unit)

    val installed = mutableStateListOf<Installed>()

    /** Running plugins' HUD contents by `pluginId/elementId`. */
    private val hudContents = mutableStateMapOf<String, @Composable () -> Unit>()

    /** Running plugins' overlays, drawn over the game view in registration order. */
    val overlays = mutableStateListOf<@Composable () -> Unit>()

    /** Each running plugin's page by plugin id. */
    val pages = mutableStateMapOf<String, @Composable () -> Unit>()

    private val ticks = CopyOnWriteArrayList<Hook<() -> Unit>>()
    private val scheduled = CopyOnWriteArrayList<Scheduled>()
    private val joins = CopyOnWriteArrayList<Hook<(String) -> Unit>>()
    private val leaves = CopyOnWriteArrayList<Hook<(String?) -> Unit>>()
    private val chats = CopyOnWriteArrayList<Hook<(ChatMessage) -> Unit>>()
    private val clicks = CopyOnWriteArrayList<Hook<(Click) -> Unit>>()
    private val keys = CopyOnWriteArrayList<Hook<(KeyPress) -> Unit>>()
    private val presses = CopyOnWriteArrayList<Hook<Pair<PluginSetting<String>, () -> Unit>>>()
    private val movements = CopyOnWriteArrayList<Hook<(Movement) -> Unit>>()
    private val messages = CopyOnWriteArrayList<Hook<Pair<String, (ByteArray) -> Unit>>>()
    private val musicTracks = CopyOnWriteArrayList<Hook<(alkaline.plugin.MusicTrack?) -> Unit>>()
    private val commands = ConcurrentHashMap<String, Command>()
    private val stateFile: Path = folder.resolve("plugins.json")

    /** The HUD elements running plugins draw, for the HUD and its editor. */
    val hudItems: List<HudContribution>
        get() = installed.flatMap { plugin ->
            val manifest = plugin.manifest ?: return@flatMap emptyList()
            val store = plugin.store ?: return@flatMap emptyList()
            if (plugin.running == null) return@flatMap emptyList()
            manifest.hud.mapNotNull { spec ->
                val content = hudContents["${plugin.id}/${spec.id}"] ?: return@mapNotNull null
                HudContribution("plugin:${plugin.id}:${spec.id}", spec.title, spec.enabled, spec.placement, store, content)
            }
        }

    /** Commands running plugins handle, by name, with their descriptions (for the Plugins section). */
    fun commandsOf(plugin: Installed): List<Pair<String, String>> =
        commands.entries.filter { it.value.owner.plugin === plugin }.map { it.key to it.value.description }.sortedBy { it.first }

    // ---- lifecycle (UI thread) ----------------------------------------------------------------------------

    /** Finds the jars in [folder] (again) and starts those left on. UI thread. */
    fun scan() {
        stopAll()
        installed.toList().forEach { closeStore(it) }
        installed.clear()
        clearLoadedCopies()
        val saved = readState()
        val jars = try {
            Files.createDirectories(folder)
            Files.list(folder).use { stream -> stream.filter { it.toString().endsWith(".jar") }.sorted().toList() }
        } catch (failure: IOException) {
            System.err.println("Cannot list plugins in $folder: ${failure.message}")
            emptyList()
        }
        for (jar in jars) {
            val plugin = read(jar)
            if (plugin.manifest != null && installed.any { it.manifest?.id == plugin.manifest.id }) {
                plugin.problems = listOf("Another jar already uses the id ${plugin.manifest.id}.")
            }
            installed += plugin
        }
        for (plugin in installed) {
            if (plugin.problems.isEmpty()) plugin.problems = listOfNotNull(dependencyProblem(plugin))
            if (plugin.problems.isEmpty()) openStore(plugin)
            plugin.wantsEnabled = saved[plugin.id] == true
        }
        for (plugin in startOrder()) {
            if (plugin.wantsEnabled && plugin.problems.isEmpty() && requiredOf(plugin).all { it.running != null }) {
                start(plugin)
            }
        }
    }

    /** Turns [plugin] on, after the plugins it needs. UI thread. */
    fun enable(plugin: Installed) {
        enableWithDependencies(plugin, mutableSetOf())
        writeState()
    }

    private fun enableWithDependencies(plugin: Installed, visiting: MutableSet<Installed>) {
        if (plugin.running != null || plugin.manifest == null || !visiting.add(plugin)) return
        for (dependency in requiredOf(plugin)) enableWithDependencies(dependency, visiting)
        // Optional dependencies that are on start first, so this plugin's loader sees them.
        if (requiredOf(plugin).all { it.running != null }) {
            plugin.wantsEnabled = true
            if (plugin.problems.isEmpty() || plugin.problems.all { it.startsWith(STOPPED) || it.startsWith(FAILED) }) {
                plugin.problems = listOfNotNull(dependencyProblem(plugin))
                if (plugin.problems.isEmpty()) start(plugin)
            }
        }
    }

    /** Turns [plugin] off, after the plugins that need it. UI thread. */
    fun disable(plugin: Installed) {
        disableWithDependents(plugin)
        writeState()
    }

    private fun disableWithDependents(plugin: Installed) {
        for (other in installed) {
            if (other.running != null && plugin.id in other.manifest?.dependencies.orEmpty()) disableWithDependents(other)
        }
        plugin.wantsEnabled = false
        stop(plugin)
    }

    /** Restarts [plugin] (and what depends on it) with fresh state. UI thread. */
    fun restart(plugin: Installed) {
        val dependents = installed.filter { it.running != null && dependsOn(it, plugin) }
        disableWithDependents(plugin)
        enable(plugin)
        dependents.forEach { enable(it) }
    }

    /** Whether [plugin] needs [other], directly or through its required dependencies. */
    private fun dependsOn(plugin: Installed, other: Installed, seen: MutableSet<Installed> = mutableSetOf()): Boolean {
        val manifest = plugin.manifest ?: return false
        if (!seen.add(plugin)) return false
        return other.id in manifest.dependencies || other.id in manifest.optionalDependencies ||
            requiredOf(plugin).any { dependsOn(it, other, seen) }
    }

    override fun close() {
        stopAll()
        installed.forEach { closeStore(it) }
        watchdog.close()
    }

    /** The time [plugin] has spent in its hooks since it started, or null when it is not running. */
    fun usage(plugin: Installed): Usage? = plugin.running?.context?.let { Usage(it.nanos.get(), it.calls.get(), it.slowest) }

    /**
     * The plugin whose code threw [failure] on the interface thread (a composable, a layout or an effect of
     * its), stopped and reported; null when no plugin's code is in the trace. The caller then rebuilds the
     * interface without it. UI thread.
     */
    fun recover(failure: Throwable): Installed? {
        val (plugin, culprit) = blame(failure) ?: return null
        val context = plugin.running?.context ?: return null
        context.alive = false
        report(context, failure, "its interface")
        stopNow(context, culprit, "its interface")
        return plugin
    }

    /** The running plugin with a frame in [failure]'s trace (or a cause's), and the throwable with that frame. */
    private fun blame(failure: Throwable): Pair<Installed, Throwable>? {
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = failure
        while (current != null && seen.add(current)) {
            for (frame in current.stackTrace) {
                val loader = frame.classLoaderName ?: continue
                if (!loader.startsWith(LOADER_PREFIX)) continue
                val id = loader.removePrefix(LOADER_PREFIX)
                installed.firstOrNull { it.id == id && it.running != null }?.let { return it to current }
            }
            current = current.cause
        }
        return null
    }

    private fun stopAll() {
        // Dependents first.
        startOrder().asReversed().forEach { stop(it) }
    }

    // ---- game hooks (render thread) -----------------------------------------------------------------------

    /** The player joined [address]; tells the server which plugin channels the client listens on. */
    fun joined(address: String) {
        for (hook in joins) guard(hook.owner, "onJoin") { hook.value(address) }
        val channels = installed.filter { it.running != null }.flatMap { it.manifest!!.channels }.distinct()
        if (channels.isNotEmpty()) {
            game?.sendMessage("REGISTER", channels.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        }
    }

    /** The player left; [reason] is the server's message, or null when the player left. */
    fun left(reason: String?) {
        for (hook in leaves) guard(hook.owner, "onLeave") { hook.value(reason) }
    }

    /** A game tick passed: scheduled tasks that are due, then tick listeners. */
    fun tick() {
        for (task in scheduled) {
            if (task.ticks-- <= 0) {
                scheduled.remove(task)
                guard(task.owner, "a scheduled task") { task.task() }
            }
        }
        for (hook in ticks) guard(hook.owner, "onTick") { hook.value() }
    }

    /** A chat message (§ formatted) from the server: the text to show, or null to hide it. */
    fun chat(text: String): String? {
        if (chats.isEmpty()) return text
        val message = ChatMessage(text)
        for (hook in chats) guard(hook.owner, "onChat") { hook.value(message) }
        return if (message.hidden) null else message.replacement ?: text
    }

    /** The player sends [line]; true when it was a plugin's command, which never reaches the server. */
    fun command(line: String): Boolean {
        if (!line.startsWith("/") || commands.isEmpty()) return false
        val parts = line.substring(1).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val name = parts.firstOrNull()?.lowercase() ?: return false
        val command = commands[name]?.takeIf { it.owner.alive } ?: return false
        val owner = command.owner
        guard(owner, "/$name") {
            command.handler(CommandCall(name, parts.drop(1), line) { reply ->
                if (owner.alive) owner.showChat("§7[${owner.plugin.name}]§r $reply")
            })
        }
        return true
    }

    /** An attack or use press in game; true when a plugin cancelled it, so the game must ignore it. */
    fun click(button: Click.Button, timeMillis: Long): Boolean {
        val click = Click(button, timeMillis)
        for (hook in clicks) guard(hook.owner, "onClick") { hook.value(click) }
        return click.cancelled
    }

    /** A key press while playing; true when a plugin cancelled it. */
    fun key(binding: String, timeMillis: Long): Boolean {
        if (keys.isEmpty()) return false
        val press = KeyPress(binding, timeMillis)
        for (hook in keys) guard(hook.owner, "onKey") { hook.value(press) }
        return press.cancelled
    }

    /** A key or mouse button ([binding]) was pressed while playing: plugins' bindings on it fire. */
    fun pressed(binding: String) {
        for (hook in presses) {
            if (hook.owner.alive && hook.value.first.value == binding) guard(hook.owner, "onPress") { hook.value.second() }
        }
    }

    /** This tick's movement input, changed by plugins; null when no plugin steers. */
    fun movement(forward: Float, strafe: Float, jump: Boolean, sneak: Boolean, sprint: Boolean): Movement? {
        if (movements.isEmpty()) return null
        val movement = Movement(forward, strafe, jump, sneak, sprint)
        for (hook in movements) guard(hook.owner, "onMovement") { hook.value(movement) }
        // NaN would survive coerceIn and reach the server as an impossible move.
        movement.forward = movement.forward.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        movement.strafe = movement.strafe.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        return movement
    }

    /** A plugin message from the server. */
    fun message(channel: String, data: ByteArray) {
        for (hook in messages) {
            if (hook.value.first == channel) guard(hook.owner, "onMessage") { hook.value.second(data.copyOf()) }
        }
    }

    /** Whether a plugin listens for clicks or keys (the client skips the hooks otherwise). */
    val listensForClicks: Boolean get() = clicks.isNotEmpty()
    val listensForKeys: Boolean get() = keys.isNotEmpty() || presses.isNotEmpty()

    /** Whether the game should publish its camera for [Game.project] (a plugin draws an overlay). */
    @Volatile
    var wantsCamera = false
        private set

    // ---- starting and stopping ----------------------------------------------------------------------------

    private fun start(plugin: Installed) {
        val manifest = plugin.manifest ?: return
        if (stampOf(plugin.jar) != plugin.stamp) {
            plugin.problems = listOf("$FAILED the jar changed after it was read; press Reload.")
            return
        }
        val copy = try {
            copyForLoading(plugin)
        } catch (failure: IOException) {
            plugin.problems = listOf("$FAILED cannot copy the jar to load it: ${failure.message}")
            return
        }
        val dependencies = (manifest.dependencies.keys + manifest.optionalDependencies.keys)
            .mapNotNull { id -> installed.firstOrNull { it.id == id }?.running }
        val loader = PluginClassLoader(manifest.id, copy.toUri().toURL(), javaClass.classLoader,
            dependencies.map { it.loader })
        val context = Context(plugin, dependencies.associate { it.context.plugin.id to it.plugin })
        var phase = "its constructor"
        try {
            watchdog.start()
            val instance = watchdog.watch(context, phase) {
                Class.forName(manifest.entrypoint, true, loader).getDeclaredConstructor().newInstance()
            }
            if (instance !is AlkalinePlugin) {
                throw IllegalArgumentException("${manifest.entrypoint} does not implement AlkalinePlugin.")
            }
            plugin.running = Running(loader, copy, instance, context)
            plugin.enabled = true
            plugin.problems = emptyList()
            phase = "enable"
            watchdog.watch(context, phase) { instance.enable(context) }
        } catch (failure: Throwable) {
            if (fatal(failure)) throw failure
            val cause = (failure as? java.lang.reflect.InvocationTargetException)?.targetException
                ?: (failure as? ExceptionInInitializerError)?.exception ?: failure
            context.alive = false
            stop(plugin)
            if (plugin.running == null) {
                closeQuietly(loader)
                deleteQuietly(copy)
            }
            plugin.problems = listOf("$FAILED ${describe(cause)}")
            appendLog(plugin, "error: failed in $phase: $cause")
            System.err.println("Plugin ${manifest.id} failed in $phase: $cause")
            cause.printStackTrace()
        }
    }

    private fun stop(plugin: Installed) {
        val running = plugin.running ?: run {
            plugin.enabled = false
            return
        }
        plugin.running = null
        plugin.enabled = false
        running.context.revokeAll()
        try {
            watchdog.watch(running.context, "disable") { running.plugin.disable() }
        } catch (failure: Throwable) {
            if (fatal(failure)) throw failure
            appendLog(plugin, "error: failed in disable: $failure")
            System.err.println("Plugin ${plugin.id} failed while stopping: $failure")
        }
        closeQuietly(running.loader)
        deleteQuietly(running.copy)
        wantsCamera = overlays.isNotEmpty()
    }

    /** Copies [plugin]'s jar into `.loaded/`, so the original can be replaced while this copy is open. */
    private fun copyForLoading(plugin: Installed): Path {
        val directory = folder.resolve(LOADED)
        Files.createDirectories(directory)
        val copy = directory.resolve("${plugin.id}-${System.nanoTime()}.jar")
        Files.copy(plugin.jar, copy)
        return copy
    }

    /** Removes copies left by an earlier run (a copy still open, on Windows, stays until next time). */
    private fun clearLoadedCopies() {
        val directory = folder.resolve(LOADED)
        if (!Files.isDirectory(directory)) return
        try {
            Files.list(directory).use { stream -> stream.toList() }.forEach { deleteQuietly(it) }
        } catch (ignored: IOException) {
            // Next scan.
        }
    }

    private fun deleteQuietly(file: Path) {
        try {
            Files.deleteIfExists(file)
        } catch (ignored: IOException) {
            // Still open (Windows); clearLoadedCopies removes it on a later scan.
        }
    }

    private fun closeQuietly(loader: URLClassLoader) {
        try {
            loader.close()
        } catch (ignored: IOException) {
            // The jar stays open until the loader is collected.
        }
    }

    /**
     * Runs a plugin's code, timed and watched, unless that run of the plugin is stopped or quarantined. One that
     * throws is quarantined at once and stopped on the UI thread, and the player told.
     */
    private inline fun guard(owner: Context, where: String, action: () -> Unit) {
        if (!owner.alive) return
        val activity = watchdog.enter(owner, where)
        val start = System.nanoTime()
        try {
            action()
        } catch (failure: Throwable) {
            if (fatal(failure)) throw failure
            fail(owner, failure, where)
        } finally {
            val elapsed = System.nanoTime() - start
            watchdog.exit(activity)
            owner.account(elapsed, where)
        }
    }

    /** Quarantines [owner] (no more hooks fire) and stops it on the UI thread. Any thread. */
    private fun fail(owner: Context, failure: Throwable, where: String) {
        if (!owner.alive) return
        owner.alive = false
        report(owner, failure, where)
        post { stopNow(owner, failure, where) }
    }

    private fun report(owner: Context, failure: Throwable, where: String) {
        System.err.println("Plugin ${owner.plugin.id} failed in $where: $failure")
        failure.printStackTrace()
        appendLog(owner.plugin, "error: failed in $where: $failure")
    }

    /** Stops the run [owner] after [failure], unless that run already ended (a restart since). UI thread. */
    private fun stopNow(owner: Context, failure: Throwable, where: String) {
        val plugin = owner.plugin
        if (plugin.running?.context !== owner) return
        disableWithDependents(plugin)
        // It stays on in plugins.json: the next start tries again (fixed jar, new conditions).
        plugin.wantsEnabled = true
        plugin.problems = listOf("$STOPPED ${describe(failure)} (in $where)")
        notify(Notification("Plugin stopped", "${plugin.name}: ${describe(failure)}", Notification.Kind.WARNING))
    }

    /** A failure as the player reads it; linkage errors usually mean the plugin was built for another API. */
    private fun describe(failure: Throwable): String = when (failure) {
        is LinkageError ->
            "${failure.javaClass.simpleName}: ${failure.message} (built against a different plugin API or library?)"
        is StackOverflowError -> "endless recursion (StackOverflowError)"
        else -> failure.message ?: failure.javaClass.simpleName
    }

    private fun appendLog(plugin: Installed, line: String) {
        val stamped = "${LocalTime.now().format(TIME)}  $line"
        post {
            plugin.log += stamped
            while (plugin.log.size > LOG_LINES) plugin.log.removeAt(0)
        }
    }

    // ---- dependencies ---------------------------------------------------------------------------------------

    private fun requiredOf(plugin: Installed): List<Installed> =
        plugin.manifest?.dependencies?.keys.orEmpty().mapNotNull { id -> installed.firstOrNull { it.id == id } }

    /** Why [plugin]'s required dependencies cannot be met, or null. */
    private fun dependencyProblem(plugin: Installed): String? {
        val manifest = plugin.manifest ?: return null
        for ((id, range) in manifest.dependencies) {
            val dependency = installed.firstOrNull { it.manifest?.id == id }
                ?: return "Needs the plugin \"$id\" ($range), which is not installed."
            val version = dependency.manifest!!.version
            if (!range.matches(version)) return "Needs ${dependency.name} $range; ${dependency.name} $version is installed."
            if (dependency.problems.isNotEmpty() && dependency.problems.none { it.startsWith(STOPPED) || it.startsWith(FAILED) }) {
                return "Needs ${dependency.name}, which cannot load."
            }
            if (dependsOn(dependency, plugin)) return "${dependency.name} and ${plugin.name} depend on each other."
        }
        for ((id, range) in manifest.optionalDependencies) {
            val dependency = installed.firstOrNull { it.manifest?.id == id } ?: continue
            if (!range.matches(dependency.manifest!!.version)) {
                appendLog(plugin, "warn: works with ${dependency.name} $range; ignoring ${dependency.manifest.version}.")
            }
        }
        return null
    }

    /** Valid plugins with each after its dependencies (required and optional). */
    private fun startOrder(): List<Installed> {
        val ordered = LinkedHashSet<Installed>()
        val visiting = mutableSetOf<Installed>()
        fun visit(plugin: Installed) {
            if (plugin in ordered || !visiting.add(plugin)) return
            val manifest = plugin.manifest ?: return
            for (id in manifest.dependencies.keys + manifest.optionalDependencies.keys) {
                installed.firstOrNull { it.id == id && it.manifest != null }?.let { visit(it) }
            }
            ordered += plugin
        }
        installed.filter { it.manifest != null }.forEach { visit(it) }
        return ordered.toList()
    }

    // ---- what a plugin sees -------------------------------------------------------------------------------

    internal inner class Context(val plugin: Installed, private val dependencies: Map<String, AlkalinePlugin>) :
        PluginContext, Game, PluginSettings, PluginLog {
        private val registrations = CopyOnWriteArrayList<Registration>()
        private val manifest = plugin.manifest!!
        private var job: kotlinx.coroutines.Job? = null

        /** False once this run is stopped or has failed: its hooks no longer fire and its actions do nothing. */
        @Volatile
        var alive = true

        val nanos = AtomicLong()
        val calls = AtomicLong()
        @Volatile
        var slowest = 0L
        @Volatile
        private var lastSlowWarning = 0L

        fun account(elapsed: Long, where: String) {
            nanos.addAndGet(elapsed)
            calls.incrementAndGet()
            if (elapsed > slowest) slowest = elapsed
            if (elapsed > SLOW_NANOS) {
                val now = System.nanoTime()
                if (now - lastSlowWarning > SLOW_WARNING_GAP) {
                    lastSlowWarning = now
                    warn("$where took ${elapsed / 1_000_000} ms; the game waits for listeners, so frames were late.")
                }
            }
        }

        override val active: Boolean get() = alive

        override val info: PluginInfo = manifest.info
        override val dataFolder: Path
            get() = folder.resolve("data").resolve(manifest.id).also { Files.createDirectories(it) }
        override val log: PluginLog get() = this
        override val settings: PluginSettings get() = this
        override val game: Game get() = this

        override val music: alkaline.plugin.Music = object : alkaline.plugin.Music {
            private fun player() = this@PluginHost.music?.player?.takeIf { alive }
            override val available: Boolean get() = this@PluginHost.music != null
            override val track: alkaline.plugin.MusicTrack?
                get() = player()?.state?.takeIf { it.status != alkaline.music.MusicPlayer.Status.STOPPED }?.track?.let(::musicTrack)
            override val playing: Boolean get() = player()?.state?.playing == true
            override val positionMillis: Long get() = player()?.positionMillis() ?: 0L
            override var volume: Float
                get() = player()?.state?.volume ?: 0f
                set(value) {
                    require(value.isFinite()) { "volume must be finite." }
                    player()?.volume(value)
                }
            override fun play(input: String) {
                if (alive) post { this@PluginHost.music?.playInput(input, enqueue = false) }
            }
            override fun enqueue(input: String) {
                if (alive) post { this@PluginHost.music?.playInput(input, enqueue = true) }
            }
            override fun pause() { player()?.pause() }
            override fun resume() { player()?.resume() }
            override fun toggle() { player()?.toggle() }
            override fun next() { player()?.next() }
            override fun previous() { player()?.previous() }
            override fun seek(millis: Long) { player()?.seek(millis) }
            override fun onTrack(listener: (alkaline.plugin.MusicTrack?) -> Unit): Registration = add(musicTracks, listener)
        }

        override val scope: CoroutineScope by lazy {
            val supervisor = SupervisorJob().also { job = it }
            CoroutineScope(supervisor + Dispatchers.Default + CoroutineExceptionHandler { _, failure ->
                fail(this, failure, "a coroutine")
            })
        }

        private fun bridge(): GameBridge? = this@PluginHost.game

        private fun track(undo: () -> Unit): Registration {
            if (!alive) {
                undo()
                throw IllegalStateException("${manifest.name} is stopped; it can no longer register anything.")
            }
            var registration: Registration? = null
            registration = Registration {
                if (registrations.remove(registration)) undo()
            }
            registrations += registration
            return registration
        }

        private fun <T> add(list: MutableList<Hook<T>>, value: T): Registration {
            val hook = Hook(this, value)
            list += hook
            return track { list.remove(hook) }
        }

        fun revokeAll() {
            alive = false
            job?.cancel()
            registrations.toList().forEach { it.remove() }
            scheduled.removeIf { it.owner === this }
        }

        // -- context

        override fun runOnUiThread(task: () -> Unit) = post { guard(this, "runOnUiThread", task) }

        private val notified = ArrayDeque<Pair<String, Long>>()

        override fun notify(title: String, text: String, kind: NotificationKind) {
            if (!alive) return
            val mapped = when (kind) {
                NotificationKind.INFO -> Notification.Kind.INFO
                NotificationKind.SUCCESS -> Notification.Kind.SUCCESS
                NotificationKind.WARNING -> Notification.Kind.WARNING
            }
            // Flood control: the same notification again within a few seconds is merged, and at most a few show
            // per window; the rest go to the log, so a notify in a tick listener cannot bury the screen.
            val key = "$title\u0000$text"
            val now = System.nanoTime()
            // True: show it; false: a repeat, merged; null: too many.
            val shown: Boolean? = synchronized(notified) {
                while (notified.isNotEmpty() && now - notified.first().second > NOTIFY_WINDOW) notified.removeFirst()
                when {
                    notified.any { it.first == key } -> false
                    notified.size >= NOTIFY_MOST -> null
                    else -> {
                        notified.addLast(key to now)
                        true
                    }
                }
            }
            when (shown) {
                true -> post { this@PluginHost.notify(Notification(title, text, mapped, manifest.name)) }
                null -> info("Notification not shown (more than $NOTIFY_MOST at once): $title${if (text.isEmpty()) "" else " - $text"}")
                false -> {}
            }
        }

        override fun hud(element: String, content: @Composable () -> Unit): Registration {
            require(manifest.hud.any { it.id == element }) {
                "The manifest declares no HUD element \"$element\" (declared: ${manifest.hud.joinToString { it.id }})."
            }
            val key = "${manifest.id}/$element"
            hudContents[key] = content
            return track { if (hudContents[key] === content) hudContents.remove(key) }
        }

        override fun overlay(content: @Composable () -> Unit): Registration {
            overlays += content
            wantsCamera = true
            return track {
                overlays.remove(content)
                wantsCamera = overlays.isNotEmpty()
            }
        }

        override fun page(content: @Composable () -> Unit): Registration {
            pages[manifest.id] = content
            return track { if (pages[manifest.id] === content) pages.remove(manifest.id) }
        }

        private var screen: PluginScreen? = null

        override fun openScreen(title: String, content: @Composable (close: () -> Unit) -> Unit) {
            post {
                if (!alive || plugin.running?.context !== this || bridge()?.inGame != true) return@post
                val opened = PluginScreen(manifest.id, title, content)
                screen = opened
                screens(opened)
                track { if (screen === opened) closeScreen() }
            }
        }

        override fun closeScreen() {
            post {
                if (screen != null) {
                    screen = null
                    screens(null)
                }
            }
        }

        override fun <T : Any> dependency(id: String, type: Class<T>): T? =
            dependencies[id]?.takeIf { type.isInstance(it) }?.let { type.cast(it) }

        // -- log

        private var logWindow = 0L
        private var logLines = 0
        private var logDropped = 0

        /** Flood control: at most [LOG_RATE] lines a second reach the console and the card; the rest are counted. */
        private fun admitLine(): Boolean = synchronized(this) {
            val now = System.nanoTime()
            if (now - logWindow > 1_000_000_000L) {
                if (logDropped > 0) {
                    val dropped = logDropped
                    logDropped = 0
                    System.err.println("[plugin ${manifest.id}] ($dropped lines not shown)")
                    appendLog(plugin, "($dropped lines not shown: more than $LOG_RATE a second)")
                }
                logWindow = now
                logLines = 0
            }
            if (logLines++ < LOG_RATE) true else {
                logDropped++
                false
            }
        }

        override fun info(message: String) {
            if (!admitLine()) return
            println("[plugin ${manifest.id}] $message")
            appendLog(plugin, message)
        }

        override fun warn(message: String, failure: Throwable?) {
            if (!admitLine()) return
            System.err.println("[plugin ${manifest.id}] warning: $message${failure?.let { " ($it)" } ?: ""}")
            appendLog(plugin, "warning: $message${failure?.let { " ($it)" } ?: ""}")
        }

        override fun error(message: String, failure: Throwable?) {
            if (!admitLine()) return
            System.err.println("[plugin ${manifest.id}] error: $message")
            failure?.printStackTrace()
            appendLog(plugin, "error: $message${failure?.let { " ($it)" } ?: ""}")
        }

        // -- settings

        private inline fun <reified S : Setting<*>, T> declared(id: String, type: String): PluginSetting<T> {
            val setting = manifest.setting(id)
                ?: throw IllegalArgumentException("The manifest declares no setting \"$id\".")
            require(setting is S) { "The setting \"$id\" is not a $type setting." }
            @Suppress("UNCHECKED_CAST")
            return Handle(id, setting as Setting<T>)
        }

        override fun boolean(id: String): PluginSetting<Boolean> = declared<Setting.BooleanSetting, Boolean>(id, "boolean")
        override fun integer(id: String): PluginSetting<Int> = declared<Setting.IntSetting, Int>(id, "integer")
        override fun decimal(id: String): PluginSetting<Float> = declared<Setting.FloatSetting, Float>(id, "decimal")
        override fun color(id: String): PluginSetting<Int> = declared<Setting.ColorSetting, Int>(id, "color")
        override fun text(id: String): PluginSetting<String> = declared<Setting.StringSetting, String>(id, "text")
        override fun choice(id: String): PluginSetting<String> = declared<Setting.OptionSetting, String>(id, "choice")
        override fun key(id: String): PluginSetting<String> = declared<Setting.KeySetting, String>(id, "key")

        private inner class Handle<T>(override val id: String, private val setting: Setting<T>) : PluginSetting<T> {
            private val store = plugin.store!!
            override val value: T get() = (plugin.snapshot?.value ?: store.snapshot()).value(setting)
            override fun set(value: T) = store.set(setting, value)
            override fun reset() = store.reset(setting)
            override fun onChange(listener: (T) -> Unit): Registration {
                val storeListener = ConfigStore.Listener { previous, current ->
                    val next = current.value(setting)
                    if (previous.value(setting) != next) guard(this@Context, "onChange") { listener(next) }
                }
                store.addListener(storeListener)
                return track { store.removeListener(storeListener) }
            }
        }

        // -- game: lifecycle

        override val inGame: Boolean get() = bridge()?.inGame == true
        override fun onJoin(listener: (address: String) -> Unit): Registration = add(joins, listener)
        override fun onLeave(listener: (reason: String?) -> Unit): Registration = add(leaves, listener)
        override fun onTick(listener: () -> Unit): Registration = add(ticks, listener)

        override fun schedule(ticks: Int, task: () -> Unit): Registration {
            val entry = Scheduled(this, ticks.coerceAtLeast(0), task)
            scheduled += entry
            return track { scheduled.remove(entry) }
        }

        // -- game: state

        private fun reading(): GameBridge? {
            val bridge = bridge() ?: return null
            check(bridge.onRenderThread()) {
                "Game state is read on the render thread: from a game listener, or inside game.schedule { }."
            }
            return bridge
        }

        override fun player(): PlayerState? = reading()?.player()
        override fun inventory(): InventoryState? = reading()?.inventory()
        override fun world(): World? = reading()?.world()
        override fun entities(): List<EntityState> = reading()?.entities().orEmpty()
        override fun entity(id: Int): EntityState? = reading()?.entity(id)
        override fun target(): Target? = reading()?.target()
        override fun server(): ServerState? = reading()?.server()
        override fun project(x: Double, y: Double, z: Double): ScreenPoint? = bridge()?.project(x, y, z)

        // -- game: actions, applied on the render thread

        private fun act(action: (GameBridge) -> Unit) {
            if (!alive) return
            val bridge = bridge() ?: return
            if (bridge.onRenderThread()) action(bridge) else bridge.runOnRenderThread {
                guard(this, "an action") { this@PluginHost.game?.let(action) }
            }
        }

        override fun showChat(text: String) = act { it.showChat(text) }

        override fun onChat(listener: (ChatMessage) -> Unit): Registration = add(chats, listener)
        override fun sendChat(message: String) {
            require(message.isNotBlank()) { "A chat message cannot be blank." }
            require(message.length <= CHAT_LIMIT) { "1.8.9 chat messages are at most $CHAT_LIMIT characters." }
            require(message.none { it == '§' || it < ' ' || it == '\u007f' }) {
                "Chat messages cannot contain § or control characters (the server kicks for them)."
            }
            act { it.sendChat(message) }
        }

        override fun command(name: String, description: String, handler: (CommandCall) -> Unit): Registration {
            val key = name.lowercase()
            require(key.matches(Regex("[a-z0-9_-]{1,32}"))) { "Command names are [a-z0-9_-]{1,32}: \"$name\"." }
            val command = Command(this, description, handler)
            val previous = commands.putIfAbsent(key, command)
            check(previous == null) { "/$key is already handled by ${previous!!.owner.plugin.name}." }
            return track { commands.remove(key, command) }
        }

        override fun onClick(listener: (Click) -> Unit): Registration = add(clicks, listener)
        override fun onKey(listener: (KeyPress) -> Unit): Registration = add(keys, listener)
        override fun onPress(binding: PluginSetting<String>, listener: () -> Unit): Registration =
            add(presses, binding to listener)
        override fun onMovement(listener: (Movement) -> Unit): Registration = add(movements, listener)
        override fun attack() = act { it.attack() }
        override fun use() = act { it.use() }
        override fun select(slot: Int) {
            require(slot in 0..8) { "Hotbar slots are 0..8." }
            act { it.select(slot) }
        }
        override fun look(yaw: Float, pitch: Float) {
            require(yaw.isFinite() && pitch.isFinite()) { "yaw and pitch must be finite (got $yaw, $pitch)." }
            act { it.look(yaw, pitch.coerceIn(-90f, 90f)) }
        }

        private fun checkSound(volume: Float, pitch: Float) {
            require(volume.isFinite() && volume >= 0f) { "volume must be finite and not negative (got $volume)." }
            require(pitch.isFinite() && pitch > 0f) { "pitch must be finite and positive (got $pitch)." }
        }

        override fun playSound(sound: String, volume: Float, pitch: Float) {
            checkSound(volume, pitch)
            if (alive) bridge()?.playSound(sound, volume, pitch)
        }

        override fun playSound(sound: String, x: Double, y: Double, z: Double, volume: Float, pitch: Float) {
            checkSound(volume, pitch)
            require(x.isFinite() && y.isFinite() && z.isFinite()) { "The position must be finite." }
            if (alive) bridge()?.playSound(sound, x, y, z, volume, pitch)
        }

        override fun onMessage(channel: String, listener: (ByteArray) -> Unit): Registration =
            add(messages, channel to listener)

        override fun sendMessage(channel: String, data: ByteArray) {
            require(channel.length in 1..20) { "Channel names are 1 to 20 characters." }
            require(data.size <= 32767) { "Plugin messages are at most 32767 bytes." }
            // Copied now: the plugin may reuse its array before the queued send runs.
            val copy = data.copyOf()
            act { it.sendMessage(channel, copy) }
        }

        override fun sendPacket(id: Int, body: ByteArray) {
            require(id in 0..LAST_SERVERBOUND) {
                "1.8.9's serverbound play packets are 0x00 to 0x${LAST_SERVERBOUND.toString(16)} (got $id)."
            }
            require(body.size <= MAX_PACKET) { "A packet body is at most $MAX_PACKET bytes." }
            val copy = body.copyOf()
            act { it.sendPacket(id, copy) }
        }
    }

    // ---- files --------------------------------------------------------------------------------------------

    private fun read(jar: Path): Installed = try {
        ZipFile(jar.toFile()).use { zip ->
            val entry = zip.getEntry(Manifest.FILE)
                ?: return Installed(jar, null, listOf("No ${Manifest.FILE} at the root of the jar."), null)
            val text = zip.getInputStream(entry).use { String(it.readNBytes(Json.MAX_CHARS), Charsets.UTF_8) }
            val result = Manifest.parse(text)
            val manifest = result.manifest
            val problems = result.problems.toMutableList()
            var icon: ByteArray? = null
            if (manifest?.icon != null) {
                val iconEntry = zip.getEntry(manifest.icon)
                when {
                    iconEntry == null -> problems += "icon: ${manifest.icon} is not in the jar."
                    iconEntry.size > MAX_ICON_BYTES -> problems += "icon: must be at most ${MAX_ICON_BYTES / 1024} KiB."
                    else -> {
                        // The size in the zip's directory can lie; the bytes read decide.
                        val bytes = zip.getInputStream(iconEntry).use { it.readNBytes(MAX_ICON_BYTES + 1) }
                        if (bytes.size > MAX_ICON_BYTES) problems += "icon: must be at most ${MAX_ICON_BYTES / 1024} KiB."
                        else icon = bytes
                    }
                }
            }
            if (manifest != null && zip.getEntry(manifest.entrypoint.replace('.', '/') + ".class") == null) {
                problems += "entrypoint: ${manifest.entrypoint} is not in the jar."
            }
            Installed(jar, manifest.takeIf { problems.isEmpty() }, problems, icon)
        }
    } catch (failure: IOException) {
        Installed(jar, null, listOf("Cannot read the jar: ${failure.message}"), null)
    }

    private fun openStore(plugin: Installed) {
        val manifest = plugin.manifest ?: return
        if (manifest.settings.isEmpty() && manifest.hud.isEmpty()) return
        val schema = ConfigSchema.builder(1).apply {
            manifest.settings.forEach { add(it) }
            manifest.hud.forEach { spec ->
                add(spec.enabled)
                add(spec.placement.anchor())
                add(spec.placement.x())
                add(spec.placement.y())
                add(spec.placement.scale())
            }
        }.build()
        val store = ConfigStore(schema, folder.resolve("data").resolve(manifest.id).resolve("settings.json"))
        val report = store.load()
        report.issues().forEach { appendLog(plugin, "warning: settings: $it") }
        val state = mutableStateOf(store.snapshot())
        store.addListener { _, current -> state.value = current }
        plugin.store = store
        plugin.snapshot = state
    }

    private fun closeStore(plugin: Installed) {
        try {
            plugin.store?.close()
        } catch (failure: IOException) {
            System.err.println("Cannot save ${plugin.id}'s settings: ${failure.message}")
        }
        plugin.store = null
        plugin.snapshot = null
    }

    private fun readState(): Map<String, Boolean> {
        if (!Files.exists(stateFile)) return emptyMap()
        return try {
            val root = Json.parse(Files.readString(stateFile)) as? Map<*, *> ?: return emptyMap()
            val plugins = root["plugins"] as? Map<*, *> ?: root
            plugins.entries.mapNotNull { (key, value) ->
                val entry = value as? Map<*, *> ?: return@mapNotNull null
                key.toString() to (entry["enabled"] == true)
            }.toMap()
        } catch (failure: Exception) {
            System.err.println("Ignoring unreadable $stateFile: ${failure.message}")
            emptyMap()
        }
    }

    private fun writeState() {
        val plugins = linkedMapOf<String, Any>()
        for (plugin in installed) {
            if (plugin.manifest == null) continue
            plugins[plugin.id] = linkedMapOf("enabled" to plugin.wantsEnabled)
        }
        try {
            Files.createDirectories(folder)
            val temporary = stateFile.resolveSibling("plugins.json.tmp")
            Files.writeString(temporary, Json.write(linkedMapOf("schema" to 1L, "plugins" to plugins)))
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: IOException) {
            System.err.println("Cannot save $stateFile: ${failure.message}")
        }
    }

    private val watchdog = Watchdog(
        stalled = { owner, where, thread, seconds, stack ->
            val trace = stack.take(STALL_FRAMES).joinToString("\n") { "    at $it" }
            System.err.println("Plugin ${owner.plugin.id} has held ${thread.name} in $where for $seconds s:\n$trace")
            appendLog(owner.plugin, "warning: not responding: ${thread.name} in $where for $seconds s, at ${stack.firstOrNull() ?: "?"}")
            post {
                notify(Notification("Plugin not responding", "${owner.plugin.name} has been busy in $where for $seconds s.",
                    Notification.Kind.WARNING))
            }
        },
        resumed = { owner, where, millis -> appendLog(owner.plugin, "warning: $where returned after $millis ms.") },
    )

    internal companion object {
        const val STOPPED = "Stopped after an error:"
        const val FAILED = "Could not start:"
        const val LOG_LINES = 100
        const val LOG_RATE = 40
        const val MAX_ICON_BYTES = 256 * 1024
        const val LOADED = ".loaded"
        const val LOADER_PREFIX = "plugin:"
        const val CHAT_LIMIT = 100
        const val LAST_SERVERBOUND = 0x19
        /** The 1.8.9 server's largest packet is 2 MiB; the body is smaller than that by the id. */
        const val MAX_PACKET = 2 * 1024 * 1024 - 8
        const val SLOW_NANOS = 50_000_000L
        const val SLOW_WARNING_GAP = 10_000_000_000L
        const val NOTIFY_WINDOW = 4_000_000_000L
        const val NOTIFY_MOST = 3
        const val STALL_FRAMES = 12
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        /** Errors the JVM cannot continue after; anything else a plugin throws only stops the plugin. */
        fun fatal(failure: Throwable): Boolean = failure is VirtualMachineError && failure !is StackOverflowError

        fun stampOf(jar: Path): Pair<Long, Long>? = try {
            Files.size(jar) to Files.getLastModifiedTime(jar).toMillis()
        } catch (failure: IOException) {
            null
        }
    }
}

/**
 * A plugin's classes: the client's first (so plugins share its Kotlin, Compose and this API), then the plugin's
 * own jar, then its dependencies' classes.
 */
internal class PluginClassLoader(
    id: String,
    jar: URL,
    parent: ClassLoader,
    private val dependencies: List<PluginClassLoader>,
) : URLClassLoader("plugin:$id", arrayOf(jar), parent) {
    override fun findClass(name: String): Class<*> {
        try {
            return super.findClass(name)
        } catch (missing: ClassNotFoundException) {
            for (dependency in dependencies) {
                try {
                    return dependency.loadClass(name)
                } catch (ignored: ClassNotFoundException) {
                    // Try the next dependency.
                }
            }
            throw missing
        }
    }

    override fun findResource(name: String): URL? =
        super.findResource(name) ?: dependencies.firstNotNullOfOrNull { it.getResource(name) }
}
