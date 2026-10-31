package alkaline.music

import alkaline.config.Json
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Plain HTTPS reads for the catalogs and cover art: short timeouts, a browser's user agent, a size cap. */
class Web {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun bytes(url: String, limit: Int = MAX_BYTES): ByteArray {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en")
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Stopped")
        }
        response.body().use { body ->
            if (response.statusCode() !in 200..299) throw IOException("${URI.create(url).host} answered ${response.statusCode()}")
            val bytes = body.readNBytes(limit + 1)
            if (bytes.size > limit) throw IOException("The answer from ${URI.create(url).host} is too large")
            return bytes
        }
    }

    fun text(url: String): String = String(bytes(url), Charsets.UTF_8)

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"

        fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)
    }
}

/** What a catalog link lists: its name (an album's, a playlist's) and its tracks. */
data class Listing(val name: String?, val tracks: List<Track>)

/** The search that finds a catalog track's recording: artist and title, as the official audio is usually named. */
internal fun matchQuery(title: String, artist: String?): String = listOfNotNull(artist, title).joinToString(" - ")

/**
 * Spotify links: tracks, albums and playlists, read from the page Spotify serves for embedding a link (its
 * `__NEXT_DATA__`), which needs no account or developer key. Spotify's audio itself is DRM-protected; each track
 * gets a [Track.match] instead.
 */
object Spotify {
    private val LINK = Regex("""(?:https?://open\.spotify\.com/(?:intl-[a-z-]+/)?|spotify:)(track|album|playlist)[/:]([A-Za-z0-9]{22})""")
    private val DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    fun recognizes(text: String): Boolean = LINK.containsMatchIn(text)

    fun listing(link: String, web: Web): Listing {
        val match = LINK.find(link) ?: throw IOException("Not a Spotify track, album or playlist link")
        val (kind, id) = match.destructured
        val page = web.text("https://open.spotify.com/embed/$kind/$id")
        val data = DATA.find(page)?.groupValues?.get(1) ?: throw IOException("Spotify's page has changed; cannot read it")
        val entity = path(Json.parse(data), "props", "pageProps", "state", "data", "entity") as? Map<*, *>
            ?: throw IOException("Spotify did not describe this link")
        val cover = ((entity["coverArt"] as? Map<*, *>)?.get("sources") as? List<*>)?.firstNotNullOfOrNull { (it as? Map<*, *>)?.get("url") as? String }
            ?: ((entity["visualIdentity"] as? Map<*, *>)?.get("image") as? List<*>)?.firstNotNullOfOrNull { (it as? Map<*, *>)?.get("url") as? String }
        if (kind == "track") {
            val title = entity["name"] as? String ?: entity["title"] as? String ?: throw IOException("Spotify gave no title")
            val artist = (entity["artists"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }?.joinToString(", ")
            return Listing(null, listOf(track(id, title, artist, null, (entity["duration"] as? Number)?.toLong(), cover)))
        }
        val name = entity["name"] as? String ?: entity["title"] as? String
        val album = if (kind == "album") name else null
        val tracks = (entity["trackList"] as? List<*>).orEmpty().mapNotNull { item ->
            val entry = item as? Map<*, *> ?: return@mapNotNull null
            val uri = entry["uri"] as? String ?: return@mapNotNull null
            val title = entry["title"] as? String ?: return@mapNotNull null
            track(uri.substringAfterLast(':'), title, entry["subtitle"] as? String, album, (entry["duration"] as? Number)?.toLong(), cover)
        }
        if (tracks.isEmpty()) throw IOException("Spotify lists no tracks for this link")
        return Listing(name, tracks)
    }

    private fun track(id: String, title: String, artist: String?, album: String?, duration: Long?, cover: String?) = Track(
        location = "https://open.spotify.com/track/$id",
        origin = Origin.SPOTIFY,
        title = title,
        artist = artist?.takeIf { it.isNotBlank() },
        album = album,
        durationMillis = duration,
        artwork = cover,
        match = matchQuery(title, artist),
    )
}

/**
 * Apple Music links: songs and albums through Apple's public iTunes lookup API, and playlists from the
 * schema.org description on their page (artists filled in by one lookup). Apple Music's audio is DRM-protected
 * too; each track gets a [Track.match].
 */
object AppleMusic {
    private val LINK = Regex("""https?://(?:music|itunes)\.apple\.com/([a-z]{2})/(album|song|playlist)/(?:[^/?#]*/)?([A-Za-z0-9.\-]+)(?:\?[^#]*?\bi=(\d+))?""")
    private val SCHEMA = Regex("""<script id=schema:music-playlist type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    fun recognizes(text: String): Boolean = LINK.containsMatchIn(text)

    fun listing(link: String, web: Web): Listing {
        val match = LINK.find(link) ?: throw IOException("Not an Apple Music song, album or playlist link")
        val (country, kind, id, track) = match.destructured
        return when {
            track.isNotEmpty() || kind == "song" -> Listing(null, lookup(listOf(track.ifEmpty { id }), country, web).values.toList()
                .ifEmpty { throw IOException("Apple Music has no such song in this country") })
            kind == "album" -> {
                val results = results(web.text("https://itunes.apple.com/lookup?id=$id&entity=song&country=$country&limit=200"))
                val name = results.firstOrNull { it["wrapperType"] == "collection" }?.get("collectionName") as? String
                Listing(name, results.mapNotNull(::trackOf).ifEmpty { throw IOException("Apple Music lists no songs for this album") })
            }
            else -> playlist(link, country, web)
        }
    }

    private fun playlist(link: String, country: String, web: Web): Listing {
        val page = web.text(link)
        val schema = SCHEMA.find(page)?.groupValues?.get(1)?.let { Json.parse(it) as? Map<*, *> }
            ?: throw IOException("Apple Music's page has changed, or the playlist is private")
        val entries = (schema["track"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val ids = entries.mapNotNull { (it["url"] as? String)?.substringAfterLast('/')?.takeIf { id -> id.all(Char::isDigit) } }
        val known = ids.chunked(150).flatMap { lookup(it, country, web).entries }.associate { it.key to it.value }
        val tracks = entries.mapNotNull { entry ->
            val id = (entry["url"] as? String)?.substringAfterLast('/')
            known[id] ?: (entry["name"] as? String)?.let { title ->
                Track(entry["url"] as? String ?: link, Origin.APPLE_MUSIC, title,
                    durationMillis = (entry["duration"] as? String)?.let(::isoMillis),
                    artwork = (entry["audio"] as? Map<*, *>)?.get("thumbnailUrl") as? String, match = title)
            }
        }
        if (tracks.isEmpty()) throw IOException("Apple Music lists no songs for this playlist")
        return Listing(schema["name"] as? String, tracks)
    }

    /** iTunes lookup of song ids, by id. */
    private fun lookup(ids: List<String>, country: String, web: Web): Map<String, Track> {
        if (ids.isEmpty()) return emptyMap()
        val results = results(web.text("https://itunes.apple.com/lookup?id=${ids.joinToString(",")}&country=$country"))
        return results.mapNotNull { result -> trackOf(result)?.let { (result["trackId"] as? Number)?.toLong().toString() to it } }.toMap()
    }

    /** Apple's catalog search: songs, for the Music page's search. */
    fun search(query: String, web: Web, country: String = "us"): List<Track> =
        results(web.text("https://itunes.apple.com/search?term=${Web.encode(query)}&entity=song&limit=20&country=$country"))
            .mapNotNull(::trackOf)

    private fun results(text: String): List<Map<*, *>> =
        ((Json.parse(text) as? Map<*, *>)?.get("results") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }

    private fun trackOf(result: Map<*, *>): Track? {
        if (result["wrapperType"] != "track" || result["kind"] != "song") return null
        val title = result["trackName"] as? String ?: return null
        val artist = result["artistName"] as? String
        return Track(
            location = (result["trackViewUrl"] as? String)?.substringBefore("&uo=") ?: return null,
            origin = Origin.APPLE_MUSIC,
            title = title,
            artist = artist,
            album = result["collectionName"] as? String,
            durationMillis = (result["trackTimeMillis"] as? Number)?.toLong(),
            // The lookup's 100 px artwork has a sibling at any size.
            artwork = (result["artworkUrl100"] as? String)?.replace("100x100bb", "600x600bb"),
            match = matchQuery(title, artist),
        )
    }

    /** An ISO 8601 duration such as `PT3M38S`, in milliseconds. */
    internal fun isoMillis(text: String): Long? {
        val match = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?""").matchEntire(text) ?: return null
        val (hours, minutes, seconds) = match.destructured
        return ((hours.toLongOrNull() ?: 0) * 3600_000 + (minutes.toLongOrNull() ?: 0) * 60_000 +
            ((seconds.toDoubleOrNull() ?: 0.0) * 1000).toLong())
    }
}

private fun path(root: Any?, vararg keys: String): Any? {
    var current = root
    for (key in keys) current = (current as? Map<*, *>)?.get(key) ?: return null
    return current
}
