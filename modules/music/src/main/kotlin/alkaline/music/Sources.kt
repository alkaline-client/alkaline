package alkaline.music

import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.nameWithoutExtension

/** Where the Music page searches. */
enum class Catalog(val label: String) { YOUTUBE("YouTube"), SOUNDCLOUD("SoundCloud"), APPLE_MUSIC("Apple Music") }

/**
 * Turns what the player adds into tracks, and tracks into audio. Everything here may block on disk, network or
 * yt-dlp: it runs on the player's background threads, never the game's or the interface's.
 */
class Sources(val ytDlp: YtDlp, val web: Web = Web()) {
    /** A track opened for playback, with what opening it taught about it (tags, a title from the site). */
    internal class Opened(val decoder: Decoder, val track: Track)

    /**
     * The tracks behind [input]: a file, a folder (every audio file in it), an M3U or PLS playlist, a Spotify or
     * Apple Music link, a page yt-dlp knows, or a stream URL.
     */
    fun find(input: String, cancelled: () -> Boolean = { false }): Listing {
        val text = input.trim().removeSurrounding("\"").removeSurrounding("'")
        if (text.isEmpty()) throw IOException("Nothing to add")
        if (Spotify.recognizes(text)) return Spotify.listing(text, web)
        if (AppleMusic.recognizes(text)) return AppleMusic.listing(text, web)
        if (text.startsWith("http://") || text.startsWith("https://")) {
            if (direct(text)) return Listing(null, listOf(streamTrack(text)))
            if (ytDlp.available) {
                val (name, tracks) = ytDlp.inspect(text, cancelled)
                if (tracks.isNotEmpty()) return Listing(name, tracks)
            }
            // Without yt-dlp, FFmpeg may still read it (a stream without an extension, a radio).
            return Listing(null, listOf(streamTrack(text)))
        }
        val path = localPath(text) ?: throw IOException("Not a file, a folder or a link")
        if (!Files.exists(path)) throw IOException("${path.fileName} does not exist")
        return files(path, cancelled)
    }

    /** Search results for [query] in [catalog]. */
    fun search(query: String, catalog: Catalog, cancelled: () -> Boolean = { false }): List<Track> = when (catalog) {
        Catalog.YOUTUBE -> ytDlp.search(query, SEARCH_RESULTS, soundCloud = false, cancelled)
        Catalog.SOUNDCLOUD -> ytDlp.search(query, SEARCH_RESULTS, soundCloud = true, cancelled)
        Catalog.APPLE_MUSIC -> AppleMusic.search(query, web)
    }

    /** A file, every audio file under a folder (sorted by path), or a playlist file's entries. */
    fun files(path: Path, cancelled: () -> Boolean = { false }): Listing {
        if (path.isDirectory()) {
            val found = Files.walk(path).use { stream ->
                stream.filter { !cancelled() && Files.isRegularFile(it) && audioFile(it) }.sorted().toList()
            }
            return Listing(path.fileName?.toString(), found.map(::fileTrack))
        }
        return when (path.extension.lowercase(Locale.ROOT)) {
            "m3u", "m3u8" -> Listing(path.nameWithoutExtension, Playlists.readM3u(path))
            "pls" -> Listing(path.nameWithoutExtension, Playlists.readPls(path))
            else -> if (audioFile(path)) Listing(null, listOf(fileTrack(path))) else throw IOException("${path.fileName} is not audio")
        }
    }

    /** Opens [track]'s audio: resolving a site's stream, or finding a catalog track's recording first. */
    internal fun open(track: Track, cancelled: () -> Boolean): Opened = when (track.origin) {
        Origin.FILE, Origin.STREAM -> {
            val decoder = Decoder.open(track.location, cancelled = cancelled)
            Opened(decoder, withTags(track, decoder.tags))
        }
        Origin.SITE -> {
            val stream = ytDlp.stream(track.location, cancelled)
            val decoder = Decoder.open(stream.url, stream.headers, cancelled)
            Opened(decoder, track.copy(
                title = if (track.title == track.location) stream.track.title else track.title,
                artist = track.artist ?: stream.track.artist,
                durationMillis = decoder.tags.durationMillis ?: stream.track.durationMillis ?: track.durationMillis,
                artwork = track.artwork ?: stream.track.artwork,
            ))
        }
        Origin.SPOTIFY, Origin.APPLE_MUSIC -> {
            if (!ytDlp.available) throw MissingTool("${track.origin.label} tracks play through yt-dlp, which is not installed")
            val query = track.match ?: matchQuery(track.title, track.artist)
            val candidates = ytDlp.search("$query audio", MATCH_CANDIDATES, soundCloud = false, cancelled)
            val best = bestMatch(track, candidates) ?: throw IOException("No recording of \"${track.title}\" found")
            val stream = ytDlp.stream(best.location, cancelled)
            Opened(Decoder.open(stream.url, stream.headers, cancelled), track)
        }
    }

    companion object {
        const val SEARCH_RESULTS = 20
        private const val MATCH_CANDIDATES = 6

        val AUDIO = setOf("mp3", "m4a", "m4b", "mp4", "aac", "flac", "ogg", "oga", "opus", "wav", "wave", "aif", "aiff",
            "aifc", "wma", "webm", "mka", "ape", "wv", "mpc", "ac3", "dts", "caf", "tta", "spx", "amr", "3gp", "alac")
        private val STREAMS = setOf("m3u8", "pls", "m3u")

        fun audioFile(path: Path): Boolean = path.extension.lowercase(Locale.ROOT) in AUDIO

        fun fileTrack(path: Path): Track = Track(path.toAbsolutePath().toString(), Origin.FILE, path.nameWithoutExtension)

        /** A URL that names a file or stream FFmpeg reads itself, by its extension. */
        fun direct(url: String): Boolean {
            val path = try {
                URI.create(url).path.orEmpty()
            } catch (invalid: IllegalArgumentException) {
                return false
            }
            val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
            return extension in AUDIO || extension in STREAMS
        }

        fun streamTrack(url: String): Track {
            val name = try {
                URI.create(url).let { uri -> uri.path?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: uri.host }
            } catch (invalid: IllegalArgumentException) {
                null
            }
            return Track(url, Origin.STREAM, name ?: url)
        }

        private fun localPath(text: String): Path? = try {
            when {
                text.startsWith("file:") -> Path.of(URI.create(text))
                text.startsWith("~/") || text == "~" -> Path.of(System.getProperty("user.home"), text.removePrefix("~").removePrefix("/"))
                else -> Path.of(text)
            }
        } catch (invalid: InvalidPathException) {
            null
        } catch (invalid: IllegalArgumentException) {
            null
        }

        /** [track] with what its file or stream says about itself, where it says something. */
        fun withTags(track: Track, tags: Tags): Track = track.copy(
            title = tags.title ?: track.title,
            artist = tags.artist ?: track.artist,
            album = tags.album ?: track.album,
            durationMillis = tags.durationMillis ?: track.durationMillis,
        )

        /**
         * The candidate most likely to be [track]'s recording: its title must contain the track's (before any
         * "(feat. ...)" or " - " part), and the closest duration wins; without durations, the first that matches.
         */
        fun bestMatch(track: Track, candidates: List<Track>): Track? {
            if (candidates.isEmpty()) return null
            val wanted = words(track.title.substringBefore(" (").substringBefore(" - "))
            val expected = track.durationMillis
            return candidates.minByOrNull { candidate ->
                val titled = words("${candidate.title} ${candidate.artist.orEmpty()}").containsAll(wanted)
                val gap = if (expected != null && candidate.durationMillis != null) {
                    kotlin.math.abs(candidate.durationMillis - expected) / 1000.0
                } else 30.0
                // Live versions and long mixes run longer; a wrong title is worse than a few seconds off.
                gap + (if (titled) 0.0 else 120.0) + (if (gap > 30) 60.0 else 0.0)
            }
        }

        private fun words(text: String): Set<String> =
            text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
    }
}
