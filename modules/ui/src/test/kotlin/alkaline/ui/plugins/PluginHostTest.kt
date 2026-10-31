package alkaline.ui.plugins

import alkaline.plugin.AlkalinePlugin
import alkaline.plugin.PluginContext
import alkaline.plugin.dependency
import alkaline.ui.hud.Notification
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Plugins on the test class path, loaded through jars that carry only their manifests. */
class TestLibrary : AlkalinePlugin {
    var greeting = "hello"
    override fun enable(context: PluginContext) {
        events += "library on"
    }
    override fun disable() {
        events += "library off"
    }
}

class TestApp : AlkalinePlugin {
    override fun enable(context: PluginContext) {
        events += "app on, library says ${context.dependency<TestLibrary>("library")?.greeting}"
        events += "limit ${context.settings.integer("limit").value}"
        context.game.onChat { if ("spam" in it.plain) it.hide() else if ("hi" in it.plain) it.replace("§ahi!") }
        context.game.command("app", "test") { call -> events += "command ${call.args}" }
        assertThrows(IllegalArgumentException::class.java) { context.hud("undeclared") {} }
        assertThrows(IllegalArgumentException::class.java) { context.settings.boolean("limit") }
    }
    override fun disable() {
        events += "app off"
    }
}

/** Misbehaves on purpose; [mode] picks how. */
class TestFaulty : AlkalinePlugin {
    override fun enable(context: PluginContext) {
        contexts += context
        when (mode) {
            "throw" -> context.game.onTick {
                events += "tick"
                error("boom")
            }
            "recurse" -> context.game.onTick { recurse(0) }
            "stall" -> context.game.onTick { Thread.sleep(2600) }
            "flood" -> {
                repeat(200) { context.log.info("line $it") }
                repeat(10) { context.notify("Same") }
                repeat(10) { context.notify("Different $it") }
            }
            "arguments" -> {
                assertThrows(IllegalArgumentException::class.java) { context.game.sendChat("§cred") }
                assertThrows(IllegalArgumentException::class.java) { context.game.sendChat("x".repeat(101)) }
                assertThrows(IllegalArgumentException::class.java) { context.game.look(Float.NaN, 0f) }
                assertThrows(IllegalArgumentException::class.java) { context.game.playSound("random.orb", -1f) }
                assertThrows(IllegalArgumentException::class.java) { context.game.sendPacket(0x40, ByteArray(0)) }
                events += "arguments checked"
            }
        }
    }

    private fun recurse(depth: Int): Int = recurse(depth + 1) + 1

    companion object {
        var mode = ""
        val contexts = mutableListOf<PluginContext>()
    }
}

/** Follows the music player through the plugin API. */
class TestListener : AlkalinePlugin {
    override fun enable(context: PluginContext) {
        contexts += context
        context.music.onTrack { track -> events += "track ${track?.title} ${track?.durationMillis}" }
    }

    companion object {
        val contexts = mutableListOf<PluginContext>()
    }
}

internal val events = java.util.Collections.synchronizedList(mutableListOf<String>())

class PluginHostTest {
    private lateinit var folder: Path
    private val notifications = java.util.Collections.synchronizedList(mutableListOf<Notification>())

    @BeforeEach
    fun setUp() {
        folder = Files.createTempDirectory("alkaline-plugins")
        events.clear()
        TestFaulty.contexts.clear()
    }

    @AfterEach
    fun tearDown() {
        folder.toFile().deleteRecursively()
    }

    private fun jar(name: String, manifest: String, entrypoint: String) {
        ZipOutputStream(Files.newOutputStream(folder.resolve(name))).use { zip ->
            zip.putNextEntry(ZipEntry("alkaline-plugin.json"))
            zip.write(manifest.toByteArray())
            zip.putNextEntry(ZipEntry(entrypoint.replace('.', '/') + ".class"))
        }
    }

    private fun manifest(id: String, version: String, entrypoint: String, extra: String = "") = """{
        "schema": 1, "id": "$id", "name": "${id.replaceFirstChar { it.uppercase() }}", "version": "$version",
        "entrypoint": "$entrypoint", "api": 1, "minecraft": "1.8.9" $extra }"""

    private fun host() = PluginHost(folder, { it() }, { notifications += it }).also { it.scan() }

    private fun installBoth(range: String = "^1.0.0") {
        jar("library.jar", manifest("library", "1.2.0", TestLibrary::class.java.name), TestLibrary::class.java.name)
        jar("app.jar", manifest("app", "0.1.0", TestApp::class.java.name, """, "dependencies": { "library": "$range" },
            "settings": [ { "id": "limit", "type": "integer", "title": "Limit", "min": 1, "max": 10, "default": 4 } ]"""),
            TestApp::class.java.name)
    }

    @Test
    fun enablingStartsDependenciesFirstAndDisablingStopsDependents() {
        installBoth()
        val host = host()
        val app = host.installed.single { it.id == "app" }
        val library = host.installed.single { it.id == "library" }
        host.enable(app)
        assertEquals(listOf("library on", "app on, library says hello", "limit 4"), events)
        assertTrue(library.enabled && app.enabled)
        host.disable(library)
        assertEquals(listOf("app off", "library off"), events.drop(3))
        assertFalse(app.enabled)
        host.close()
    }

    @Test
    fun rememberedPluginsStartInDependencyOrder() {
        installBoth()
        host().apply { enable(installed.single { it.id == "app" }) }.close()
        events.clear()
        val host = host()
        assertEquals(listOf("library on", "app on, library says hello", "limit 4"), events)
        host.close()
    }

    @Test
    fun unmetDependenciesAreReported() {
        installBoth(range = "^2.0.0")
        val host = host()
        val app = host.installed.single { it.id == "app" }
        assertEquals(listOf("Needs Library ^2.0.0; Library 1.2.0 is installed."), app.problems)
        host.enable(app)
        assertFalse(app.enabled)
        Files.delete(folder.resolve("library.jar"))
        host.scan()
        assertTrue(host.installed.single().problems.single().startsWith("Needs the plugin \"library\""))
        host.close()
    }

    @Test
    fun chatAndCommandHooks() {
        installBoth()
        val host = host()
        host.enable(host.installed.single { it.id == "app" })
        assertNull(host.chat("§7some spam here"))
        assertEquals("§ahi!", host.chat("hi there"))
        assertEquals("plain", host.chat("plain"))
        assertTrue(host.command("/app one two"))
        assertFalse(host.command("/other"))
        assertFalse(host.command("app"))
        assertEquals("command [one, two]", events.last())
        assertEquals(listOf("/app" to "test").map { it.first.drop(1) to it.second },
            host.commandsOf(host.installed.single { it.id == "app" }))
        host.close()
    }

    @Test
    fun badJarsExplainThemselves() {
        ZipOutputStream(Files.newOutputStream(folder.resolve("empty.jar"))).use { it.putNextEntry(ZipEntry("a.txt")) }
        jar("missing.jar", manifest("missing", "1.0.0", "example.Missing"), "example.Other")
        jar("same.jar", manifest("library", "1.0.0", TestLibrary::class.java.name), TestLibrary::class.java.name)
        jar("zsame.jar", manifest("library", "1.0.0", TestLibrary::class.java.name), TestLibrary::class.java.name)
        val host = host()
        val problems = host.installed.associate { it.jar.fileName.toString() to it.problems }
        assertEquals(listOf("No alkaline-plugin.json at the root of the jar."), problems["empty.jar"])
        assertEquals(listOf("entrypoint: example.Missing is not in the jar."), problems["missing.jar"])
        assertEquals(emptyList<String>(), problems["same.jar"])
        assertEquals(listOf("Another jar already uses the id library."), problems["zsame.jar"])
        host.close()
    }

    @Test
    fun instancesAreSharedWithDependents() {
        installBoth()
        val host = host()
        host.enable(host.installed.single { it.id == "app" })
        val library = host.installed.single { it.id == "library" }
        assertSame(TestLibrary::class.java, library.running!!.plugin.javaClass)
        host.close()
    }

    private fun faulty(mode: String): PluginHost.Installed {
        TestFaulty.mode = mode
        jar("faulty.jar", manifest("faulty", "1.0.0", TestFaulty::class.java.name), TestFaulty::class.java.name)
        return host().installed.single()
    }

    @Test
    fun aFailingListenerIsQuarantinedBeforeItIsStopped() {
        TestFaulty.mode = "throw"
        jar("faulty.jar", manifest("faulty", "1.0.0", TestFaulty::class.java.name), TestFaulty::class.java.name)
        val posted = mutableListOf<() -> Unit>()
        val host = PluginHost(folder, { posted += it }, { notifications += it })
        host.scan()
        val plugin = host.installed.single()
        host.enable(plugin)
        posted.clear()
        host.tick()
        host.tick()
        host.tick()
        // Called once: the failure quarantined it although the UI thread has not stopped it yet.
        assertEquals(listOf("tick"), events)
        assertTrue(plugin.enabled)
        posted.toList().forEach { it() }
        assertFalse(plugin.enabled)
        assertEquals("Stopped after an error: boom (in onTick)", plugin.problems.single())
        assertEquals("Plugin stopped", notifications.single().title)
        host.close()
    }

    @Test
    fun aStaleRunCannotTouchARestartedOne() {
        val plugin = faulty("")
        val host = PluginHost(folder, { it() }, { notifications += it })
        host.scan()
        val installed = host.installed.single()
        host.enable(installed)
        val old = TestFaulty.contexts.single()
        host.restart(installed)
        assertEquals(2, TestFaulty.contexts.size)
        assertFalse(old.active)
        assertTrue(TestFaulty.contexts.last().active)
        old.runOnUiThread { error("late") }
        assertThrows(IllegalStateException::class.java) { old.game.onTick {} }
        assertTrue(installed.enabled)
        assertTrue(installed.problems.isEmpty())
        host.close()
        assertTrue(plugin.problems.isEmpty())
    }

    @Test
    fun endlessRecursionStopsOnlyThePlugin() {
        faulty("recurse")
        val host = host()
        val plugin = host.installed.single()
        host.enable(plugin)
        host.tick()
        assertFalse(plugin.enabled)
        assertTrue(plugin.problems.single().startsWith("Stopped after an error: endless recursion"), plugin.problems.single())
        host.close()
    }

    @Test
    fun interfaceFailuresAreBlamedOnThePluginInTheTrace() {
        installBoth()
        val host = host()
        val app = host.installed.single { it.id == "app" }
        host.enable(app)
        val failure = IllegalStateException("drawn badly").apply {
            stackTrace = arrayOf(
                StackTraceElement("app", null, null, "androidx.compose.ui.Layout", "measure", "Layout.kt", 1),
                StackTraceElement("plugin:app", null, null, "example.Hud", "invoke", "Hud.kt", 12),
            )
        }
        assertNull(host.recover(IllegalStateException("not a plugin's")))
        assertSame(app, host.recover(RuntimeException("wrapped", failure)))
        assertFalse(app.enabled)
        assertEquals("Stopped after an error: drawn badly (in its interface)", app.problems.single())
        assertTrue(host.installed.single { it.id == "library" }.enabled)
        host.close()
    }

    @Test
    fun jarsLoadFromCopiesAndAChangedJarAsksForReload() {
        val plugin = faulty("")
        val host = PluginHost(folder, { it() }, { notifications += it })
        host.scan()
        host.enable(host.installed.single())
        val loaded = folder.resolve(".loaded")
        assertEquals(1, Files.list(loaded).use { it.count() })
        // The original can go while the plugin runs from its copy.
        Files.delete(folder.resolve("faulty.jar"))
        host.disable(host.installed.single())
        assertEquals(0, Files.list(loaded).use { it.count() })
        jar("faulty.jar", manifest("faulty", "1.0.0", TestFaulty::class.java.name), TestFaulty::class.java.name)
        host.scan()
        jar("faulty.jar", manifest("faulty", "1.0.1", TestFaulty::class.java.name, ", \"description\": \"new\""),
            TestFaulty::class.java.name)
        Files.setLastModifiedTime(folder.resolve("faulty.jar"), java.nio.file.attribute.FileTime.fromMillis(1))
        host.enable(host.installed.single())
        assertFalse(host.installed.single().enabled)
        assertEquals("Could not start: the jar changed after it was read; press Reload.", host.installed.single().problems.single())
        host.close()
        assertTrue(plugin.problems.isEmpty())
    }

    @Test
    fun logsAndNotificationsAreFloodControlled() {
        faulty("flood")
        val host = host()
        val plugin = host.installed.single()
        host.enable(plugin)
        assertEquals(PluginHost.LOG_RATE, plugin.log.size)
        assertEquals(listOf("Same", "Different 0", "Different 1"), notifications.map { it.title })
        host.close()
    }

    @Test
    fun badArgumentsAreRejectedAtTheCall() {
        faulty("arguments")
        val host = host()
        host.enable(host.installed.single())
        assertEquals(listOf("arguments checked"), events)
        host.close()
    }

    @Test
    fun theWatchdogReportsAStalledListener() {
        faulty("stall")
        val host = host()
        val plugin = host.installed.single()
        host.enable(plugin)
        host.tick()
        assertEquals("Plugin not responding", notifications.single().title)
        assertTrue(plugin.enabled)
        assertTrue(plugin.log.any { "returned after" in it }, plugin.log.joinToString())
        assertTrue(host.usage(plugin)!!.slowestNanos >= 2_600_000_000L)
        host.close()
    }

    @Test
    fun pluginsFollowAndControlTheMusicPlayer() {
        jar("listener.jar", manifest("listener", "1.0.0", TestListener::class.java.name), TestListener::class.java.name)
        val output = object : alkaline.music.Output {
            @Volatile var written = 0L
            override fun write(samples: ShortArray) { written += samples.size / 2 }
            override fun bufferedFrames() = written
            override fun playedFrames() = 0L
            override fun flush() { written = 0 }
            override fun gain(value: Float) {}
            override fun paused(value: Boolean) {}
        }
        val player = alkaline.music.MusicPlayer(output, alkaline.music.Sources(alkaline.music.YtDlp(folder.resolve("tools"))),
            alkaline.music.Library(folder.resolve("music")))
        val music = alkaline.ui.music.MusicController(player, { it() }, { _, _, _ -> })
        val host = host()
        host.music = music
        host.enable(host.installed.single())
        val context = TestListener.contexts.last()
        assertTrue(context.music.available)
        val wav = folder.resolve("tone.wav")
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + 16000).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(16000)
        Files.write(wav, header.array() + ByteArray(16000))
        context.music.volume = 0.5f
        context.music.play(wav.toString())
        val deadline = System.nanoTime() + 5_000_000_000L
        while (events.none { it.startsWith("track tone") }) {
            check(System.nanoTime() < deadline) { "No track event; events $events" }
            Thread.sleep(10)
        }
        assertEquals("track tone 1000", events.first { it.startsWith("track") })
        assertEquals("tone", context.music.track?.title)
        assertEquals(0.5f, context.music.volume)
        host.disable(host.installed.single())
        assertEquals(null, context.music.track)
        music.close()
        player.close()
        host.close()
    }
}
