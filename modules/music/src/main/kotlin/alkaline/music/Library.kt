package alkaline.music

import alkaline.config.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

/** M3U (extended or plain) and PLS playlist files, as other players write them. */
object Playlists {
    fun readM3u(file: Path): List<Track> {
        val folder = file.toAbsolutePath().parent
        var pending: Pair<Long?, String?>? = null
        val tracks = mutableListOf<Track>()
        for (raw in Files.readAllLines(file)) {
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF:") -> {
                    val info = line.removePrefix("#EXTINF:")
                    val seconds = info.substringBefore(',').substringBefore(' ').toDoubleOrNull()
                    pending = seconds?.takeIf { it > 0 }?.let { (it * 1000).toLong() } to info.substringAfter(',', "").trim().ifEmpty { null }
                }
                line.startsWith("#") -> {}
                else -> {
                    tracks += entry(line, folder, pending?.second, pending?.first)
                    pending = null
                }
            }
        }
        return tracks
    }

    fun readPls(file: Path): List<Track> {
        val folder = file.toAbsolutePath().parent
        val values = Files.readAllLines(file).mapNotNull { line ->
            val key = line.substringBefore('=', "").trim().lowercase(Locale.ROOT)
            if (key.isEmpty()) null else key to line.substringAfter('=').trim()
        }.toMap()
        return (1..10_000).asSequence().mapNotNull { index ->
            val location = values["file$index"] ?: return@mapNotNull null
            entry(location, folder, values["title$index"], values["length$index"]?.toLongOrNull()?.takeIf { it > 0 }?.times(1000))
        }.toList()
    }

    /** One entry: a URL, or a path relative to the playlist; "Artist - Title" names are split. */
    private fun entry(location: String, folder: Path, name: String?, duration: Long?): Track {
        val base = if (location.startsWith("http://") || location.startsWith("https://")) {
            Sources.streamTrack(location)
        } else {
            val path = try {
                if (location.startsWith("file:")) Path.of(java.net.URI.create(location)) else folder.resolve(location.replace('\\', '/'))
            } catch (invalid: Exception) {
                folder.resolve(location)
            }
            Sources.fileTrack(path.normalize())
        }
        val artist = name?.substringBefore(" - ", "")?.trim()?.ifEmpty { null }
        val title = if (artist != null) name.substringAfter(" - ").trim() else name
        return base.copy(title = title ?: base.title, artist = artist, durationMillis = duration)
    }

    /** Writes [tracks] as an extended M3U (UTF-8), the format every player reads. */
    fun writeM3u(file: Path, tracks: List<Track>) {
        val text = buildString {
            appendLine("#EXTM3U")
            for (track in tracks) {
                val seconds = track.durationMillis?.let { it / 1000 } ?: -1
                appendLine("#EXTINF:$seconds,${listOfNotNull(track.artist, track.title).joinToString(" - ")}")
                appendLine(track.location)
            }
        }
        Files.writeString(file, text)
    }
}

/**
 * What the player keeps on disk, in its folder (`music/` beside the client's settings):
 * - `playlists/<id>.json`: each playlist, written atomically.
 * - `state.json`: the queue and the player's choices (volume, shuffle, repeat, folders).
 * - `library.tsv`: tags read from the music folders, keyed by path, size and modification time, so a rescan only
 *   reads new and changed files.
 */
class Library(val directory: Path) {
    private val playlistFolder = directory.resolve("playlists")
    private val libraryFile = directory.resolve("library.tsv")
    private val stateFile = directory.resolve("state.json")

    // ---- playlists ------------------------------------------------------------------------------------------

    fun playlists(): List<Playlist> {
        if (!Files.isDirectory(playlistFolder)) return emptyList()
        return Files.list(playlistFolder).use { stream -> stream.filter { it.toString().endsWith(".json") }.toList() }
            .mapNotNull { file ->
                try {
                    val root = Json.parse(Files.readString(file)) as? Map<*, *> ?: return@mapNotNull null
                    Playlist(file.fileName.toString().removeSuffix(".json"), root["name"] as? String ?: "Playlist",
                        (root["tracks"] as? List<*>).orEmpty().mapNotNull(Track::fromJson))
                } catch (failure: Exception) {
                    System.err.println("Music: ignoring unreadable playlist $file: ${failure.message}")
                    null
                }
            }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun newPlaylistId(): String = UUID.randomUUID().toString().substring(0, 8)

    fun save(playlist: Playlist) {
        write(playlistFolder.resolve("${playlist.id}.json"), linkedMapOf("schema" to 1L, "name" to playlist.name,
            "tracks" to playlist.tracks.map { it.toJson() }))
    }

    fun delete(id: String) {
        Files.deleteIfExists(playlistFolder.resolve("$id.json"))
    }

    // ---- state ----------------------------------------------------------------------------------------------

    fun readState(): Map<*, *> = try {
        if (Files.exists(stateFile)) Json.parse(Files.readString(stateFile)) as? Map<*, *> ?: emptyMap<Any, Any>() else emptyMap<Any, Any>()
    } catch (failure: Exception) {
        System.err.println("Music: ignoring unreadable ${stateFile.fileName}: ${failure.message}")
        emptyMap<Any, Any>()
    }

    fun writeState(state: Map<String, Any?>) = write(stateFile, state)

    private fun write(file: Path, value: Any) {
        try {
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(temporary, Json.write(value))
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: IOException) {
            System.err.println("Music: cannot save ${file.fileName}: ${failure.message}")
        }
    }

    // ---- library --------------------------------------------------------------------------------------------

    private data class Cached(val size: Long, val modified: Long, val track: Track)

    /** The last scan's tracks, as cached; empty before the first scan. */
    fun cached(): List<Track> = readCache().values.map { it.track }.let(::sorted)

    /**
     * Every audio file under [folders] with its tags, new and changed files probed with FFmpeg ([progress] gets
     * the count done and the total). Slow for a large new library; it runs on a background thread.
     */
    fun scan(folders: List<Path>, cancelled: () -> Boolean, progress: (done: Int, total: Int) -> Unit): List<Track> {
        val cache = readCache()
        val files = folders.filter { Files.isDirectory(it) }.flatMap { folder ->
            try {
                Files.walk(folder).use { stream -> stream.filter { Files.isRegularFile(it) && Sources.audioFile(it) }.toList() }
            } catch (failure: IOException) {
                System.err.println("Music: cannot read $folder: ${failure.message}")
                emptyList()
            }
        }.distinct()
        val fresh = LinkedHashMap<String, Cached>()
        for ((index, file) in files.withIndex()) {
            if (cancelled()) break
            val key = file.toAbsolutePath().toString()
            val size = runCatching { Files.size(file) }.getOrDefault(-1L)
            val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(-1L)
            val known = cache[key]?.takeIf { it.size == size && it.modified == modified }
            fresh[key] = known ?: Cached(size, modified, try {
                Sources.withTags(Sources.fileTrack(file), Decoder.probe(key, cancelled).first)
            } catch (failure: IOException) {
                Sources.fileTrack(file)
            })
            if (index % 25 == 0 || index == files.lastIndex) progress(index + 1, files.size)
        }
        if (!cancelled()) writeCache(fresh)
        return sorted(fresh.values.map { it.track })
    }

    private fun sorted(tracks: List<Track>): List<Track> = tracks.sortedWith(
        compareBy<Track>({ (it.artist ?: "\uFFFF").lowercase(Locale.ROOT) }, { (it.album ?: "").lowercase(Locale.ROOT) },
            { it.title.lowercase(Locale.ROOT) }))

    private fun readCache(): Map<String, Cached> {
        if (!Files.exists(libraryFile)) return emptyMap()
        return try {
            Files.readAllLines(libraryFile).drop(1).mapNotNull { line ->
                val fields = line.split('\t').map(::unescape)
                if (fields.size < 7) return@mapNotNull null
                val track = Track(fields[0], Origin.FILE, fields[4], fields[5].ifEmpty { null }, fields[6].ifEmpty { null },
                    fields[3].toLongOrNull())
                fields[0] to Cached(fields[1].toLongOrNull() ?: -1, fields[2].toLongOrNull() ?: -1, track)
            }.toMap()
        } catch (failure: IOException) {
            emptyMap()
        }
    }

    private fun writeCache(entries: Map<String, Cached>) {
        try {
            Files.createDirectories(directory)
            val temporary = libraryFile.resolveSibling("library.tsv.tmp")
            Files.newBufferedWriter(temporary).use { out ->
                out.write("# Alkaline music library cache 1: path, size, modified, duration, title, artist, album\n")
                for ((path, cached) in entries) {
                    val track = cached.track
                    out.write(listOf(path, cached.size.toString(), cached.modified.toString(), track.durationMillis?.toString().orEmpty(),
                        track.title, track.artist.orEmpty(), track.album.orEmpty()).joinToString("\t") { escape(it) })
                    out.write("\n")
                }
            }
            Files.move(temporary, libraryFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: IOException) {
            System.err.println("Music: cannot save the library cache: ${failure.message}")
        }
    }

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")
    private fun unescape(text: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (char != '\\' || index >= text.length) {
                out.append(char)
                continue
            }
            when (val next = text[index++]) {
                't' -> out.append('\t')
                'n' -> out.append('\n')
                else -> out.append(next)
            }
        }
        return out.toString()
    }

    companion object {
        /** The platform's music folder: XDG's on Linux, the user's Music folder on Windows. */
        fun defaultFolders(): List<Path> {
            val home = Path.of(System.getProperty("user.home"))
            val xdg = try {
                val dirs = home.resolve(".config/user-dirs.dirs")
                if (Files.exists(dirs)) Files.readAllLines(dirs).firstOrNull { it.startsWith("XDG_MUSIC_DIR=") }
                    ?.substringAfter('=')?.trim('"')?.replace("\$HOME", home.toString())?.let { Path.of(it) } else null
            } catch (failure: Exception) {
                null
            }
            return listOf(xdg ?: home.resolve("Music"))
        }
    }
}
