package alkaline.music

import alkaline.config.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * yt-dlp, the open-source program that finds the audio behind a page on YouTube, SoundCloud, Bandcamp and
 * [about two thousand other sites](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md). Alkaline does
 * not bundle it: it runs the copy the player installed (on the `PATH`, in Alkaline's `tools` folder, or at the
 * path in [configured]). The interface is its documented output template: `-O "%(.{field,...})j"` prints one JSON
 * object of the named fields per video or playlist entry, so the output stays small (a long playlist included) and
 * a yt-dlp update needs no Alkaline update.
 *
 * YouTube needs a JavaScript runtime beside yt-dlp (Deno by default). When Deno is missing and Node.js is
 * installed, Alkaline asks yt-dlp to use Node.
 */
class YtDlp(private val tools: Path, private val configured: () -> String? = { null }) {
    /** A site's audio, ready for FFmpeg. */
    class Stream(val url: String, val headers: Map<String, String>, val track: Track)

    /** The program, or null when none is installed. */
    fun executable(): Path? {
        configured()?.takeIf { it.isNotBlank() }?.let { Path.of(it) }?.takeIf { Files.isExecutable(it) }?.let { return it }
        for (name in NAMES) {
            tools.resolve(name).takeIf { Files.isExecutable(it) }?.let { return it }
        }
        return onPath(*NAMES)
    }

    val available: Boolean get() = executable() != null

    /** The installed version, such as `2026.10.26`, or null. */
    fun version(): String? = try {
        run(listOf("--version"), VERSION_SECONDS) { false }.trim().takeIf { it.isNotEmpty() }
    } catch (failure: IOException) {
        null
    }

    /** The tracks behind [url]: one for a video or a song, every entry for a playlist, album or channel. */
    fun inspect(url: String, cancelled: () -> Boolean): Pair<String?, List<Track>> {
        val entries = lines(run(listOf("--flat-playlist", "--no-warnings", "-O", ENTRY, "--", url), INSPECT_SECONDS, cancelled))
        val single = entries.size == 1 && entries[0]["playlist_title"] == null
        return (if (single) null else entries.firstNotNullOfOrNull { it["playlist_title"] as? String }) to
            entries.mapNotNull { trackOf(it, if (single) url else null) }
    }

    /** The first [count] results for [query] on YouTube (or SoundCloud). */
    fun search(query: String, count: Int, soundCloud: Boolean, cancelled: () -> Boolean): List<Track> {
        val prefix = if (soundCloud) "scsearch" else "ytsearch"
        return lines(run(listOf("--flat-playlist", "--no-warnings", "-O", ENTRY, "--", "$prefix$count:$query"), INSPECT_SECONDS, cancelled))
            .mapNotNull { trackOf(it, null) }
    }

    /** The audio stream of the page at [url]: its direct URL, the headers to fetch it with, and what it is. */
    fun stream(url: String, cancelled: () -> Boolean): Stream {
        val info = lines(run(listOf("--no-playlist", "--no-warnings", "-f", FORMAT, "-O", STREAM, "--", url), STREAM_SECONDS, cancelled))
            .lastOrNull() ?: throw IOException("yt-dlp printed nothing")
        val direct = info["url"] as? String ?: throw IOException("yt-dlp found no audio")
        val headers = (info["http_headers"] as? Map<*, *>).orEmpty().entries
            .mapNotNull { (key, value) -> (key as? String)?.let { k -> (value as? String)?.let { k to it } } }.toMap()
        return Stream(direct, headers, trackOf(info, url) ?: Track(url, Origin.SITE, url))
    }

    private fun trackOf(info: Map<*, *>, fallback: String?): Track? {
        val page = info["webpage_url"] as? String ?: (info["url"] as? String)?.takeIf { it.startsWith("http") }
            ?: (info["id"] as? String)?.takeIf { (info["ie_key"] as? String) == "Youtube" }?.let { "https://www.youtube.com/watch?v=$it" }
            ?: fallback ?: return null
        val title = info["track"] as? String ?: info["title"] as? String ?: page
        val artist = info["artist"] as? String ?: info["creator"] as? String ?: info["uploader"] as? String
            ?: info["channel"] as? String
        val thumbnail = info["thumbnail"] as? String
            ?: (info["thumbnails"] as? List<*>)?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String }
        return Track(
            location = page,
            origin = Origin.SITE,
            title = title,
            artist = artist?.removeSuffix(" - Topic"),
            album = info["album"] as? String,
            durationMillis = (info["duration"] as? Number)?.let { (it.toDouble() * 1000).toLong() },
            artwork = thumbnail,
        )
    }

    /** One JSON object per printed line (other lines, such as a plugin's chatter, are skipped). */
    private fun lines(text: String): List<Map<*, *>> = text.lineSequence().map { it.trim() }.filter { it.startsWith("{") }
        .mapNotNull {
            try {
                Json.parse(it) as? Map<*, *>
            } catch (failure: Json.JsonException) {
                null
            }
        }.toList()

    /** Runs yt-dlp with [arguments]; its standard output, or [IOException] with its last error line. */
    private fun run(arguments: List<String>, seconds: Long, cancelled: () -> Boolean): String {
        val program = executable() ?: throw MissingTool(MISSING)
        val command = mutableListOf(program.toString())
        if (arguments.firstOrNull() != "--version" && onPath("deno", "deno.exe") == null && onPath("node", "node.exe") != null) {
            command += listOf("--js-runtimes", "node")
        }
        command += arguments
        val process = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(NULL_INPUT)).start()
        // Read both pipes on their own threads, so neither fills up and stalls the program.
        val output = StringBuilder()
        val errors = StringBuilder()
        val readers = listOf(process.inputStream to output, process.errorStream to errors).map { (stream, into) ->
            Thread {
                stream.bufferedReader().useLines { lines -> lines.forEach { synchronized(into) { into.appendLine(it) } } }
            }.apply {
                isDaemon = true
                start()
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled()) throw IOException("Stopped")
                if (System.nanoTime() > deadline) throw IOException("yt-dlp took longer than $seconds s")
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Stopped")
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
        readers.forEach { it.join(2000) }
        if (process.exitValue() != 0) {
            val reason = synchronized(errors) { errors.lineSequence().lastOrNull { it.contains("ERROR") } ?: errors.lineSequence().lastOrNull { it.isNotBlank() } }
            throw IOException(reason?.removePrefix("ERROR: ")?.trim() ?: "yt-dlp failed (exit ${process.exitValue()})")
        }
        return synchronized(output) { output.toString() }
    }

    companion object {
        const val MISSING = "yt-dlp is not installed"
        const val HOMEPAGE = "https://github.com/yt-dlp/yt-dlp#installation"
        private val NAMES = arrayOf("yt-dlp", "yt-dlp.exe", "yt-dlp_linux")
        private const val VERSION_SECONDS = 10L
        private const val INSPECT_SECONDS = 60L
        private const val STREAM_SECONDS = 45L
        private val NULL_INPUT = File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")

        /**
         * Audio FFmpeg can stream: progressive HTTP first, then HLS; DASH segment lists last, since FFmpeg cannot read
         * yt-dlp's fragment list. A video with sound when the site offers no audio alone.
         */
        /** The fields printed for each entry of a page, a playlist or a search. */
        const val ENTRY = "%(.{id,ie_key,url,webpage_url,title,track,artist,creator,uploader,channel,album,duration," +
            "thumbnail,playlist_title})j"

        /** The fields printed for a stream: the chosen format's URL and headers, and what it is. */
        const val STREAM = "%(.{url,http_headers,webpage_url,title,track,artist,creator,uploader,channel,album," +
            "duration,thumbnail})j"

        const val FORMAT = "bestaudio[protocol=https]/bestaudio[protocol=http]/bestaudio[protocol*=m3u8]/" +
            "bestaudio/best[protocol=https]/best"

        /** The first of [names] in a directory of the `PATH`. */
        fun onPath(vararg names: String): Path? {
            val path = System.getenv("PATH") ?: return null
            for (directory in path.split(File.pathSeparatorChar)) {
                if (directory.isBlank()) continue
                for (name in names) {
                    val candidate = try {
                        Path.of(directory, name)
                    } catch (invalid: java.nio.file.InvalidPathException) {
                        continue
                    }
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate
                }
            }
            return null
        }
    }
}

/** A track needs a program that is not installed (yt-dlp): trying the next track would fail the same way. */
class MissingTool(message: String) : IOException(message)
