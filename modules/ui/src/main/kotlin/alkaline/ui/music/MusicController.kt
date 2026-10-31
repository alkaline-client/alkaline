package alkaline.ui.music

import alkaline.music.Catalog
import alkaline.music.Listing
import alkaline.music.MusicPlayer
import alkaline.music.Playlist
import alkaline.music.Playlists
import alkaline.music.Sources
import alkaline.music.Track
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** The Music section's tabs. */
enum class MusicTab(val label: String) { QUEUE("Queue"), PLAYLISTS("Playlists"), LIBRARY("Library"), SEARCH("Search") }

/**
 * The music player as the interface sees it: the player's state mirrored into Compose state, and the slow work
 * (searching, reading links, scanning folders, saving playlists) on a background thread, its results posted back.
 * Lives outside the composition, so a rebuilt scene keeps the tab, the search and its results.
 */
class MusicController(
    val player: MusicPlayer,
    private val post: (() -> Unit) -> Unit,
    private val notify: (title: String, text: String, warning: Boolean) -> Unit,
) : AutoCloseable {
    /** Results of a search or of a link: what the Search tab lists. */
    data class Results(val query: String, val title: String?, val tracks: List<Track>, val link: Boolean)

    var state by mutableStateOf(player.state)
        private set
    var tab by mutableStateOf(MusicTab.QUEUE)
    var query by mutableStateOf("")
    var catalog by mutableStateOf(Catalog.YOUTUBE)
    var results by mutableStateOf<Results?>(null)
        private set
    /** What the background thread is doing, for a progress line; null when idle. */
    var busy by mutableStateOf<String?>(null)
        private set
    var playlists by mutableStateOf(emptyList<Playlist>())
        private set
    var library by mutableStateOf(emptyList<Track>())
        private set
    /** Files scanned and found while a library scan runs. */
    var scanProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    /** yt-dlp's version, "" when it is not installed, null until checked. */
    var ytDlpVersion by mutableStateOf<String?>(null)
        private set

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "alkaline-music-ui").apply { isDaemon = true }
    }
    @Volatile private var searchGeneration = 0
    private var scan: Future<*>? = null
    @Volatile private var closed = false

    /** Called on the UI thread when the entry being heard changes, or playback stops (null). */
    var onTrack: (Track?) -> Unit = {}
    /** The entry last announced through [onTrack]; loading does not count, only playing (or paused) and stopping. */
    private var announced: Long? = player.state.entry?.id?.takeIf { player.state.status != MusicPlayer.Status.STOPPED }

    init {
        player.onChange = { next ->
            post {
                state = next
                val heard = when (next.status) {
                    MusicPlayer.Status.PLAYING, MusicPlayer.Status.PAUSED -> next.entry?.id
                    MusicPlayer.Status.STOPPED -> null
                    MusicPlayer.Status.LOADING -> announced
                }
                if (heard != announced) {
                    announced = heard
                    onTrack(next.track.takeIf { heard != null })
                }
            }
        }
        background {
            val lists = player.library.playlists()
            val cached = player.library.cached()
            val version = player.sources.ytDlp.version() ?: ""
            post {
                playlists = lists
                library = cached
                ytDlpVersion = version
            }
            if (cached.isEmpty()) post { scan() }
        }
    }

    // ---- search and links -----------------------------------------------------------------------------------

    /** Searches [query] in [catalog], or reads it when it is a link or a path. */
    fun submit() {
        val text = query.trim()
        if (text.isEmpty()) return
        val generation = ++searchGeneration
        val link = looksLikeLink(text)
        busy = if (link) "Reading the link…" else "Searching ${catalog.label}…"
        val where = catalog
        background {
            val outcome = try {
                if (link) player.sources.find(text) { closed || searchGeneration != generation }.let { Results(text, it.name, it.tracks, true) }
                else Results(text, null, player.sources.search(text, where) { closed || searchGeneration != generation }, false)
            } catch (failure: IOException) {
                post {
                    if (searchGeneration == generation) {
                        busy = null
                        notify(if (link) "Cannot read that" else "Search failed", failure.message ?: "", true)
                    }
                }
                return@background
            }
            post {
                if (searchGeneration == generation) {
                    busy = null
                    results = outcome
                }
            }
        }
    }

    /** Plays (or with [enqueue], queues) whatever [input] names, as a plugin asks: a link, a path, or a YouTube search. */
    fun playInput(input: String, enqueue: Boolean) {
        background {
            val text = input.trim()
            val tracks = try {
                if (looksLikeLink(text)) player.sources.find(text) { closed }.tracks
                else player.sources.search(text, Catalog.YOUTUBE) { closed }.take(1)
            } catch (failure: IOException) {
                post { notify("Cannot play that", failure.message ?: text, true) }
                return@background
            }
            if (tracks.isEmpty()) post { notify("Nothing found", text, true) }
            else if (enqueue) player.enqueue(tracks) else player.play(tracks)
        }
    }

    fun clearResults() {
        searchGeneration++
        busy = null
        results = null
    }

    // ---- library --------------------------------------------------------------------------------------------

    /** Reads the music folders again (only new and changed files are probed). */
    fun scan() {
        if (scan?.isDone == false) return
        val folders = state.folders
        scanProgress = 0 to 0
        scan = background {
            val tracks = player.library.scan(folders, { closed }) { done, total -> post { scanProgress = done to total } }
            post {
                library = tracks
                scanProgress = null
            }
        }
    }

    fun addFolder(text: String): Boolean {
        val path = try {
            Path.of(text.trim().replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home")))
        } catch (invalid: Exception) {
            null
        }
        if (path == null || !Files.isDirectory(path)) {
            notify("Not a folder", text, true)
            return false
        }
        val folders = (state.folders + path.toAbsolutePath().normalize()).distinct()
        player.folders(folders)
        state = state.copy(folders = folders)
        post { scan() }
        return true
    }

    fun removeFolder(folder: Path) {
        val remaining = state.folders - folder
        player.folders(remaining)
        state = state.copy(folders = remaining)
        post { scan() }
    }

    // ---- playlists ------------------------------------------------------------------------------------------

    fun createPlaylist(name: String, tracks: List<Track>): Playlist {
        val playlist = Playlist(player.library.newPlaylistId(), name.trim().ifEmpty { "Playlist" }, tracks)
        savePlaylist(playlist)
        notify("Playlist saved", "${playlist.name} · ${tracks.size} tracks", false)
        return playlist
    }

    fun addToPlaylist(playlist: Playlist, tracks: List<Track>) {
        savePlaylist(playlist.copy(tracks = playlist.tracks + tracks))
        notify("Added to ${playlist.name}", if (tracks.size == 1) tracks[0].title else "${tracks.size} tracks", false)
    }

    fun removeFromPlaylist(playlist: Playlist, index: Int) {
        if (index in playlist.tracks.indices) savePlaylist(playlist.copy(tracks = playlist.tracks.filterIndexed { at, _ -> at != index }))
    }

    fun movePlaylistTrack(playlist: Playlist, index: Int, to: Int) {
        if (index !in playlist.tracks.indices || to !in playlist.tracks.indices) return
        val tracks = playlist.tracks.toMutableList()
        tracks.add(to, tracks.removeAt(index))
        savePlaylist(playlist.copy(tracks = tracks))
    }

    fun renamePlaylist(playlist: Playlist, name: String) {
        if (name.isNotBlank()) savePlaylist(playlist.copy(name = name.trim()))
    }

    fun deletePlaylist(playlist: Playlist) {
        playlists = playlists.filter { it.id != playlist.id }
        background { player.library.delete(playlist.id) }
    }

    /** Writes [playlist] as an M3U file into the first music folder, where other players find it too. */
    fun exportPlaylist(playlist: Playlist) {
        val folder = state.folders.firstOrNull { Files.isDirectory(it) } ?: Path.of(System.getProperty("user.home"))
        val file = folder.resolve(playlist.name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".m3u8")
        background {
            try {
                Playlists.writeM3u(file, playlist.tracks)
                post { notify("Playlist exported", file.toString(), false) }
            } catch (failure: IOException) {
                post { notify("Cannot export the playlist", failure.message ?: "", true) }
            }
        }
    }

    private fun savePlaylist(playlist: Playlist) {
        playlists = (playlists.filter { it.id != playlist.id } + playlist).sortedBy { it.name.lowercase() }
        background { player.library.save(playlist) }
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private fun background(task: () -> Unit): Future<*> = worker.submit {
        try {
            task()
        } catch (failure: RuntimeException) {
            System.err.println("Music: ${failure.message}")
            failure.printStackTrace()
            post {
                busy = null
                notify("Music", failure.message ?: failure.javaClass.simpleName, true)
            }
        }
    }

    override fun close() {
        closed = true
        player.onChange = {}
        worker.shutdownNow()
    }

    companion object {
        /** A URL, a Spotify URI or a path (absolute, home-relative or with a separator) rather than words to search. */
        fun looksLikeLink(text: String): Boolean = text.startsWith("http://") || text.startsWith("https://") ||
            text.startsWith("spotify:") || text.startsWith("file:") || text.startsWith("/") || text.startsWith("~/") ||
            Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(text) ||
            (text.contains('/') && !text.contains(' ') && runCatching { Sources.audioFile(Path.of(text)) }.getOrDefault(false))
    }
}
