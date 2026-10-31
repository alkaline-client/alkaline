package alkaline.music

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SourcesTest {
    private lateinit var folder: Path

    @BeforeEach
    fun setUp() {
        folder = Files.createTempDirectory("alkaline-sources")
    }

    @AfterEach
    fun tearDown() {
        folder.toFile().deleteRecursively()
    }

    @Test
    fun recognizesCatalogLinks() {
        assertTrue(Spotify.recognizes("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc"))
        assertTrue(Spotify.recognizes("https://open.spotify.com/intl-de/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertTrue(Spotify.recognizes("spotify:album:1ATL5GLyefJaxhQzSPVrLX"))
        assertFalse(Spotify.recognizes("https://open.spotify.com/artist/0gxyHStUsqpMadRV0Di1Qt"))
        assertTrue(AppleMusic.recognizes("https://music.apple.com/us/album/never-gonna-give-you-up/1773292758?i=1773293184"))
        assertTrue(AppleMusic.recognizes("https://music.apple.com/gb/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb"))
        assertFalse(AppleMusic.recognizes("https://www.apple.com/music/"))
        assertEquals(218_000L, AppleMusic.isoMillis("PT3M38S"))
        assertEquals(3_723_500L, AppleMusic.isoMillis("PT1H2M3.5S"))
    }

    @Test
    fun picksTheRecordingByTitleAndDuration() {
        val wanted = Track("https://open.spotify.com/track/x", Origin.SPOTIFY, "Never Gonna Give You Up", "Rick Astley",
            durationMillis = 213_573)
        val candidates = listOf(
            Track("https://y/1", Origin.SITE, "Never Gonna Give You Up (Live 2023)", "Rick Astley", durationMillis = 260_000),
            Track("https://y/2", Origin.SITE, "Together Forever", "Rick Astley", durationMillis = 213_000),
            Track("https://y/3", Origin.SITE, "Never Gonna Give You Up", "Rick Astley - Topic", durationMillis = 214_000),
        )
        assertEquals("https://y/3", Sources.bestMatch(wanted, candidates)?.location)
        assertNull(Sources.bestMatch(wanted, emptyList()))
    }

    @Test
    fun recognizesDirectStreams() {
        assertTrue(Sources.direct("https://example.com/radio/live.mp3"))
        assertTrue(Sources.direct("https://example.com/hls/index.m3u8?token=1"))
        assertFalse(Sources.direct("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("live", Sources.streamTrack("https://example.com/radio/live.mp3").title)
    }

    @Test
    fun readsAndWritesPlaylistFiles() {
        val music = Files.createDirectories(folder.resolve("Music"))
        val tracks = listOf(
            Track(music.resolve("a.flac").toString(), Origin.FILE, "Alpha", "Ana", durationMillis = 61_000),
            Track("https://example.com/b.mp3", Origin.STREAM, "Beta"),
        )
        val m3u = music.resolve("mix.m3u8")
        Playlists.writeM3u(m3u, tracks)
        val read = Playlists.readM3u(m3u)
        assertEquals(listOf("Alpha", "Beta"), read.map { it.title })
        assertEquals("Ana", read[0].artist)
        assertEquals(61_000L, read[0].durationMillis)
        assertEquals(Origin.STREAM, read[1].origin)
        Files.writeString(music.resolve("radio.pls"), "[playlist]\nFile1=sub/c.ogg\nTitle1=Gamma\nLength1=-1\nFile2=http://radio.example/live\nNumberOfEntries=2\n")
        val pls = Playlists.readPls(music.resolve("radio.pls"))
        assertEquals(music.resolve("sub/c.ogg").toString(), pls[0].location)
        assertEquals(listOf("Gamma", "live"), pls.map { it.title })
    }

    @Test
    fun findsFilesInFolders() {
        val music = Files.createDirectories(folder.resolve("Music/Album"))
        Files.writeString(music.resolve("02 b.mp3"), "")
        Files.writeString(music.resolve("01 a.flac"), "")
        Files.writeString(music.resolve("cover.jpg"), "")
        val listing = Sources(YtDlp(folder)).find(folder.resolve("Music").toString())
        assertEquals(listOf("01 a", "02 b"), listing.tracks.map { it.title })
        assertThrows(java.io.IOException::class.java) { Sources(YtDlp(folder)).find(folder.resolve("nothing").toString()) }
    }

    /** yt-dlp's interface: field-selected JSON lines, errors on standard error, exit status. */
    @Test
    fun drivesYtDlpThroughItsOutput() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        val tools = Files.createDirectories(folder.resolve("tools"))
        val script = tools.resolve("yt-dlp")
        Files.writeString(script, """
            #!/bin/sh
            for last; do :; done
            case "${'$'}last" in
              *fail*) echo "ERROR: [youtube] fail: Video unavailable" >&2; exit 1 ;;
              ytsearch*) echo '{"id": "s1", "ie_key": "Youtube", "url": "https://www.youtube.com/watch?v=s1", "title": "Result", "channel": "Band - Topic", "duration": 200.5}' ;;
              *list*) echo '{"url": "https://y/1", "title": "One", "playlist_title": "Mix", "duration": 60}'
                      echo '{"url": "https://y/2", "title": "Two", "playlist_title": "Mix", "duration": 61}' ;;
              *) echo '{"url": "https://cdn/audio.webm", "http_headers": {"User-Agent": "UA", "Referer": "https://y"}, "webpage_url": "https://y/v", "title": "Song", "artist": "Singer", "duration": 180}' ;;
            esac
        """.trimIndent())
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        val ytDlp = YtDlp(tools)
        assertTrue(ytDlp.available)
        val (name, entries) = ytDlp.inspect("https://y/list", { false })
        assertEquals("Mix", name)
        assertEquals(listOf("One", "Two"), entries.map { it.title })
        val results = ytDlp.search("query", 5, soundCloud = false) { false }
        assertEquals("Band", results.single().artist)
        assertEquals(200_500L, results.single().durationMillis)
        val stream = ytDlp.stream("https://y/v") { false }
        assertEquals("https://cdn/audio.webm", stream.url)
        assertEquals("https://y", stream.headers["Referer"])
        assertEquals("Singer", stream.track.artist)
        val failure = assertThrows(java.io.IOException::class.java) { ytDlp.stream("https://y/fail") { false } }
        assertEquals("[youtube] fail: Video unavailable", failure.message)
    }

    /** Reads real Spotify and Apple Music links; run with `-Dalkaline.music.online=true`. */
    @Test
    fun readsCatalogsOnline() {
        assumeTrue(System.getProperty("alkaline.music.online") == "true")
        val web = Web()
        val song = Spotify.listing("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT", web).tracks.single()
        println("Spotify track: $song")
        assertEquals("Rick Astley", song.artist)
        val playlist = Spotify.listing("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M", web)
        println("Spotify playlist: ${playlist.name}, ${playlist.tracks.size} tracks, first ${playlist.tracks.first()}")
        assertTrue(playlist.tracks.size >= 20)
        val apple = AppleMusic.listing("https://music.apple.com/us/album/never-gonna-give-you-up/1773292758?i=1773293184", web)
        println("Apple song: ${apple.tracks.single()}")
        val album = AppleMusic.listing("https://music.apple.com/us/album/3-originals/1559523357", web)
        println("Apple album: ${album.name}, ${album.tracks.size} songs")
        val list = AppleMusic.listing("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb", web)
        println("Apple playlist: ${list.name}, ${list.tracks.size} songs, first ${list.tracks.first()}")
        assertTrue(list.tracks.count { it.artist != null } >= list.tracks.size / 2)
        println("Apple search: ${AppleMusic.search("daft punk one more time", web).take(2)}")
    }
}
