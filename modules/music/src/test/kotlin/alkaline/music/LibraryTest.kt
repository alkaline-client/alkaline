package alkaline.music

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LibraryTest {
    private lateinit var folder: Path

    @BeforeEach
    fun setUp() {
        folder = Files.createTempDirectory("alkaline-library")
    }

    @AfterEach
    fun tearDown() {
        folder.toFile().deleteRecursively()
    }

    @Test
    fun playlistsRoundTrip() {
        val library = Library(folder.resolve("music"))
        val tracks = listOf(
            Track("/music/a.flac", Origin.FILE, "Alpha", "Ana", "First", 61_000),
            Track("https://open.spotify.com/track/x", Origin.SPOTIFY, "Beta", "Bo", durationMillis = 200_000,
                artwork = "https://i.example/x.jpg", match = "Bo - Beta"),
        )
        val id = library.newPlaylistId()
        library.save(Playlist(id, "Mix\twith\nodd \"names\"", tracks))
        library.save(Playlist(library.newPlaylistId(), "Another", emptyList()))
        val read = library.playlists()
        assertEquals(listOf("Another", "Mix\twith\nodd \"names\""), read.map { it.name })
        assertEquals(tracks, read[1].tracks)
        library.delete(id)
        assertEquals(listOf("Another"), library.playlists().map { it.name })
    }

    @Test
    fun scansOnlyNewAndChangedFiles() {
        val music = Files.createDirectories(folder.resolve("Music/Artist"))
        wav(music.resolve("one.wav"))
        wav(music.resolve("two\twith tab.wav"))
        Files.writeString(music.resolve("notes.txt"), "not audio")
        val library = Library(folder.resolve("music"))
        val progress = mutableListOf<Pair<Int, Int>>()
        val first = library.scan(listOf(folder.resolve("Music")), { false }) { done, total -> progress += done to total }
        assertEquals(listOf("one", "two\twith tab"), first.map { it.title })
        assertEquals(1000L, first[0].durationMillis)
        assertEquals(2 to 2, progress.last())
        // The cache answers the second scan for unchanged files; a broken file keeps its name.
        Files.writeString(music.resolve("three.mp3"), "broken")
        val second = library.scan(listOf(folder.resolve("Music")), { false }) { _, _ -> }
        assertEquals(listOf("one", "three", "two\twith tab"), second.map { it.title }.sorted())
        assertEquals(second.toSet(), Library(folder.resolve("music")).cached().toSet())
    }

    private fun wav(file: Path) {
        val rate = 8000
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + rate * 2).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(rate * 2)
        Files.write(file, header.array() + ByteArray(rate * 2))
    }
}
