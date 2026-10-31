package alkaline.music

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** A feed whose clock the test moves: written frames are "heard" only when [advance] says so. */
class FakeOutput : Output {
    @Volatile var written = 0L
    @Volatile var played = 0L
    @Volatile var gain = -1f
    @Volatile var paused = true
    var flushes = 0

    @Synchronized override fun write(samples: ShortArray) {
        written += samples.size / 2
    }
    @Synchronized override fun bufferedFrames() = written - played
    @Synchronized override fun playedFrames() = played
    @Synchronized override fun flush() {
        written = 0
        played = 0
        flushes++
    }
    override fun gain(value: Float) {
        gain = value
    }
    override fun paused(value: Boolean) {
        paused = value
    }

    /** Hears up to [frames] more of what was written. */
    @Synchronized fun advance(frames: Long) {
        played = minOf(written, played + frames)
    }
}

class MusicPlayerTest {
    private lateinit var folder: Path
    private val output = FakeOutput()
    private lateinit var player: MusicPlayer

    @BeforeEach
    fun setUp() {
        folder = Files.createTempDirectory("alkaline-player")
        player = MusicPlayer(output, Sources(YtDlp(folder.resolve("tools"))), Library(folder.resolve("music")))
    }

    @AfterEach
    fun tearDown() {
        player.close()
        folder.toFile().deleteRecursively()
    }

    private fun tone(name: String, seconds: Double): Track {
        val rate = 48_000
        val frames = (seconds * rate).toInt()
        val data = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { data.putShort(1000) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(frames * 2)
        val file = folder.resolve("$name.wav")
        Files.write(file, header.array() + data.array())
        return Sources.fileTrack(file)
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $what; state ${player.state}" }
            Thread.sleep(5)
        }
    }

    /** Hears everything written so far, a little at a time, until [condition]. */
    private fun playUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $what; state ${player.state}" }
            output.advance(4_800)
            Thread.sleep(2)
        }
    }

    @Test
    fun tracksFollowEachOtherWithoutAGap() {
        val tracks = listOf(tone("a", 0.5), tone("b", 0.5), tone("c", 0.5))
        player.play(tracks)
        waitFor("playing") { player.state.status == MusicPlayer.Status.PLAYING }
        // All three are written back to back (2 s ahead covers them): 1.5 s of frames, one flush only.
        waitFor("all written") { output.written >= 72_000 }
        assertEquals(72_000L, output.written)
        assertEquals(1, output.flushes)
        assertEquals(0, player.state.current)
        output.advance(30_000)
        waitFor("second heard") { player.state.current == 1 }
        // 30 000 frames heard: all of a (24 000) and 6 000 of b.
        assertEquals(125L, player.positionMillis())
        playUntil("stopped") { player.state.status == MusicPlayer.Status.STOPPED }
        assertEquals(2, player.state.current)
        assertEquals("c", player.state.track?.title)
    }

    @Test
    fun repeatOneReplaysAndSkippingMovesOn() {
        player.repeat(Repeat.ONE)
        player.play(listOf(tone("a", 0.25), tone("b", 0.25)))
        waitFor("a written twice") { output.written >= 24_000 }
        output.advance(13_000)
        Thread.sleep(50)
        assertEquals(0, player.state.current)
        player.next()
        waitFor("b") { player.state.current == 1 && player.state.status == MusicPlayer.Status.PLAYING }
    }

    @Test
    fun repeatAllWrapsAround() {
        player.repeat(Repeat.ALL)
        player.play(listOf(tone("a", 0.25), tone("b", 0.25)))
        playUntil("wrapped to a again") { output.played > 24_000 + 6_000 && player.state.current == 0 }
        assertEquals(MusicPlayer.Status.PLAYING, player.state.status)
    }

    @Test
    fun aTrackThatCannotOpenIsSkipped() {
        val missing = Sources.fileTrack(folder.resolve("missing.flac"))
        player.play(listOf(missing, tone("b", 0.25)))
        waitFor("b playing") { player.state.status == MusicPlayer.Status.PLAYING }
        assertEquals("b", player.state.track?.title)
        assertTrue(player.state.error!!.startsWith("missing:"), player.state.error)
    }

    @Test
    fun seeksInPlaceAndPausesTheFeed() {
        player.play(listOf(tone("long", 3.0)))
        waitFor("playing") { player.state.status == MusicPlayer.Status.PLAYING }
        assertTrue(!output.paused)
        player.seek(2_000)
        waitFor("seeked") { output.flushes == 2 }
        waitFor("rewritten") { output.written >= 48_000 }
        assertEquals(2_000L, player.positionMillis())
        output.advance(24_000)
        assertEquals(2_500L, player.positionMillis())
        player.pause()
        waitFor("paused") { player.state.status == MusicPlayer.Status.PAUSED }
        assertTrue(output.paused)
        player.toggle()
        waitFor("resumed") { player.state.status == MusicPlayer.Status.PLAYING }
        assertTrue(!output.paused)
    }

    @Test
    fun theQueueCanBeEditedWhilePlaying() {
        val a = tone("a", 2.5)
        player.play(listOf(a))
        waitFor("playing") { player.state.status == MusicPlayer.Status.PLAYING }
        player.enqueue(listOf(tone("c", 0.25)))
        player.enqueue(listOf(tone("b", 0.25)), next = true)
        waitFor("three") { player.state.queue.size == 3 }
        assertEquals(listOf("a", "b", "c"), player.state.queue.map { it.track.title })
        val c = player.state.queue[2]
        player.move(c.id, 1)
        waitFor("moved") { player.state.queue[1].id == c.id }
        player.remove(player.state.queue[2].id)
        waitFor("removed") { player.state.queue.size == 2 }
        playUntil("c heard") { player.state.track?.title == "c" }
    }

    @Test
    fun volumeFollowsACubicCurveAndPersists() {
        player.volume(0.5f)
        waitFor("gain") { output.gain == 0.125f }
        assertEquals(-18.06f, MusicPlayer.decibels(0.5f)!!, 0.01f)
        player.shuffle(true)
        player.play(listOf(tone("a", 0.25)))
        waitFor("playing") { player.state.status == MusicPlayer.Status.PLAYING }
        player.close()
        val again = MusicPlayer(FakeOutput(), Sources(YtDlp(folder.resolve("tools"))), Library(folder.resolve("music")))
        assertEquals(0.5f, again.state.volume)
        assertTrue(again.state.shuffle)
        assertEquals(listOf("a"), again.state.queue.map { it.track.title })
        again.close()
    }
}
