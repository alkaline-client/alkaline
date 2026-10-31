package alkaline.music

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DecoderTest {
    private lateinit var folder: Path

    @BeforeEach
    fun setUp() {
        folder = Files.createTempDirectory("alkaline-decoder")
    }

    @AfterEach
    fun tearDown() {
        folder.toFile().deleteRecursively()
    }

    /** A mono 16-bit WAV of [seconds] of a 1 kHz tone at [rate]. */
    private fun wav(seconds: Double, rate: Int): Path {
        val frames = (seconds * rate).toInt()
        val data = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until frames) data.putShort((sin(2 * PI * 1000 * index / rate) * 12000).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(frames * 2)
        val file = folder.resolve("tone.wav")
        Files.write(file, header.array() + data.array())
        return file
    }

    private fun framesOf(decoder: Decoder): Long {
        var frames = 0L
        while (true) frames += (decoder.read() ?: break).size / 2
        return frames
    }

    @Test
    fun decodesToStereoAtTheFeedRate() {
        val file = wav(2.0, 44_100)
        Decoder.open(file.toString()).use { decoder ->
            assertEquals(2000L, decoder.tags.durationMillis)
            assertEquals("pcm_s16le", decoder.tags.codec)
            val first = decoder.read()!!
            // Mono comes out on both channels.
            assertEquals(first[200], first[201])
            val frames = first.size / 2 + framesOf(decoder)
            assertTrue(abs(frames - 96_000) < 64, "frames $frames")
        }
    }

    @Test
    fun seeksToTheSample() {
        val file = wav(3.0, 48_000)
        Decoder.open(file.toString()).use { decoder ->
            decoder.read()
            decoder.seek(2000)
            val rest = framesOf(decoder)
            // A WAV frame is 4096 samples at most, so the seek lands within one of them.
            assertTrue(abs(rest - 48_000) <= 4096, "rest $rest")
        }
    }

    @Test
    fun badInputsExplainThemselves() {
        val text = folder.resolve("notes.mp3").also { Files.writeString(it, "not audio at all") }
        assertThrows(java.io.IOException::class.java) { Decoder.open(text.toString()) }
        assertThrows(java.io.IOException::class.java) { Decoder.open(folder.resolve("missing.flac").toString()) }
        assertThrows(java.io.IOException::class.java) { Decoder.open(text.toString(), cancelled = { true }) }
    }

    @Test
    fun probesWithoutDecoding() {
        val (tags, cover) = Decoder.probe(wav(1.0, 22_050).toString())
        assertEquals(1000L, tags.durationMillis)
        assertNull(cover)
        assertNull(tags.gainDb)
    }

    /** Decodes every file in the folder `-Dalkaline.music.probe` names (formats made by a local ffmpeg). */
    @Test
    fun probeFolder() {
        val probe = System.getProperty("alkaline.music.probe") ?: return
        Files.list(Path.of(probe)).use { it.sorted().toList() }.forEach { file ->
            val started = System.nanoTime()
            Decoder.open(file.toString()).use { decoder ->
                val frames = framesOf(decoder)
                val tags = decoder.tags
                println("${file.fileName}: ${tags.codec} in ${tags.container}, ${tags.title} / ${tags.artist}, " +
                    "${tags.durationMillis} ms, gain ${tags.gainDb}, $frames frames (${frames * 1000 / 48_000} ms), " +
                    "cover ${decoder.cover?.size}, %.1f ms".format((System.nanoTime() - started) / 1e6))
                assertNotNull(tags.durationMillis)
                assertTrue(frames > 0)
            }
        }
    }
}
