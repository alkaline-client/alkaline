package alkaline.music

/** Where a track's audio comes from, which decides how it is opened. */
enum class Origin(val label: String) {
    /** A file on this computer. */
    FILE("File"),

    /** A URL FFmpeg reads directly: an audio file on a server, an HLS stream, an internet radio. */
    STREAM("Stream"),

    /** A page on a video or music site (YouTube, SoundCloud, Bandcamp...) that yt-dlp resolves to its audio. */
    SITE("Web"),

    /**
     * A Spotify or Apple Music track. Their own audio is DRM-protected and cannot play outside their apps, so the
     * player plays the same recording found through yt-dlp ([Track.match]).
     */
    SPOTIFY("Spotify"),
    APPLE_MUSIC("Apple Music"),
}

/** A track: what the player keeps in its queue, playlists and library. Immutable. */
data class Track(
    /** A file path, or the URL that was added: a stream, a site's page, a Spotify or Apple Music link. */
    val location: String,
    val origin: Origin,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMillis: Long? = null,
    /** A cover image URL; files carry their own covers. */
    val artwork: String? = null,
    /** For Spotify and Apple Music tracks: the search that finds this recording's audio. */
    val match: String? = null,
) {
    /** The line under the title: the artist, else the album, else the file's folder or where it comes from. */
    val byline: String get() = artist ?: album ?: if (origin == Origin.FILE) {
        location.replace('\\', '/').substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { origin.label }
    } else origin.label

    fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "location" to location,
        "origin" to origin.name.lowercase(),
        "title" to title,
    ).apply {
        artist?.let { put("artist", it) }
        album?.let { put("album", it) }
        durationMillis?.let { put("duration", it) }
        artwork?.let { put("artwork", it) }
        match?.let { put("match", it) }
    }

    companion object {
        /** A track from [toJson]'s map; null when it is not one. */
        fun fromJson(value: Any?): Track? {
            val map = value as? Map<*, *> ?: return null
            val location = map["location"] as? String ?: return null
            val origin = (map["origin"] as? String)?.let { name -> Origin.entries.firstOrNull { it.name.lowercase() == name } }
                ?: return null
            return Track(
                location = location,
                origin = origin,
                title = map["title"] as? String ?: location,
                artist = map["artist"] as? String,
                album = map["album"] as? String,
                durationMillis = (map["duration"] as? Number)?.toLong(),
                artwork = map["artwork"] as? String,
                match = map["match"] as? String,
            )
        }
    }
}

/** A named list of tracks the player keeps; stored as `playlists/<id>.json`. */
data class Playlist(val id: String, val name: String, val tracks: List<Track>)

/** How the queue goes on at its end or a track's. */
enum class Repeat { OFF, ALL, ONE }

/** Formats [millis] as `m:ss` (or `h:mm:ss`). */
fun formatTime(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
