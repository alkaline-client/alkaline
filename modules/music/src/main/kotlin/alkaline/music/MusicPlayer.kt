package alkaline.music

import alkaline.audio.AudioEngine
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** Where the player's PCM goes (interleaved stereo at [AudioEngine.MusicFeed.RATE]): the music feed, or a test's. */
interface Output {
    fun write(samples: ShortArray)
    fun bufferedFrames(): Long
    fun playedFrames(): Long
    fun flush()
    fun gain(value: Float)
    fun paused(value: Boolean)

    companion object {
        fun of(feed: AudioEngine.MusicFeed): Output = object : Output {
            override fun write(samples: ShortArray) = feed.write(samples)
            override fun bufferedFrames() = feed.bufferedFrames()
            override fun playedFrames() = feed.playedFrames()
            override fun flush() = feed.flush()
            override fun gain(value: Float) = feed.gain(value)
            override fun paused(value: Boolean) = feed.paused(value)
        }
    }
}

/**
 * The music player: a queue of tracks decoded one after another into the audio engine's [AudioEngine.MusicFeed].
 *
 * One thread ("alkaline-music") owns the queue and the decoders; every public function only queues a command, so
 * none blocks the caller (the interface, a key press, a plugin). Opening a track (a disk read, a yt-dlp call, a
 * server's first bytes) runs on helper threads while the player stays responsive. The next track is opened
 * [PREFETCH_MILLIS] before the current one ends and written straight after it, so tracks follow without a gap.
 *
 * [state] is an immutable snapshot, replaced on each change and announced through [onChange] (on the player
 * thread); [positionMillis] reads the feed's clock and is safe to call every frame.
 */
class MusicPlayer(
    private val feed: Output,
    val sources: Sources,
    val library: Library,
) : AutoCloseable {
    enum class Status { STOPPED, LOADING, PLAYING, PAUSED }

    /** One place in the queue; the same track may be queued twice. */
    class Entry internal constructor(val id: Long, internal val order: Long, val track: Track)

    data class State(
        val queue: List<Entry> = emptyList(),
        /** The entry being heard (or last heard), or -1. */
        val current: Int = -1,
        val status: Status = Status.STOPPED,
        /** 0..1, as the slider shows it; the gain is [loudness] of it. */
        val volume: Float = 0.6f,
        val shuffle: Boolean = false,
        val repeat: Repeat = Repeat.OFF,
        /** Apply ReplayGain and R128 tags, so loud and quiet tracks play alike. */
        val normalize: Boolean = true,
        val folders: List<Path> = emptyList(),
        /** Why the last track could not play, until the next one starts. */
        val error: String? = null,
        /** What a radio says it plays now. */
        val liveTitle: String? = null,
        /** The current entry's cover image (JPEG or PNG bytes), when it has one. */
        val cover: ByteArray? = null,
    ) {
        val entry: Entry? get() = queue.getOrNull(current)
        val track: Track? get() = entry?.track
        val playing: Boolean get() = status == Status.PLAYING || status == Status.LOADING
    }

    @Volatile
    var state: State = State()
        private set

    /** Called on the player thread after each change of [state]. */
    @Volatile
    var onChange: (State) -> Unit = {}

    private val commands = LinkedBlockingQueue<() -> Unit>()
    private val openers: ExecutorService = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "alkaline-music-open").apply { isDaemon = true }
    }
    private val thread = Thread(::loop, "alkaline-music").apply { isDaemon = true }
    @Volatile private var running = true
    private val ids = AtomicLong()

    // ---- player-thread state --------------------------------------------------------------------------------

    private val queue = ArrayList<Entry>()
    /** Where an entry's audio starts in the feed (frames since the last flush), and the track time it starts at. */
    private class Segment(val entry: Long, val frame: Long, val offsetMillis: Long, val durationMillis: Long?)
    @Volatile private var segments: List<Segment> = emptyList()
    private var heard: Long? = null
    private var written = 0L
    /** The frame where the queue's audio ends; once heard, the player stops. */
    private var endFrame: Long? = null

    private class Opening(val entry: Long, val offsetMillis: Long, val cancelled: AtomicBoolean, val result: Future<Sources.Opened>)

    private class Decoding(val entry: Long, val opened: Sources.Opened, val gain: Float, var decodedMillis: Long)

    private var decoding: Decoding? = null
    private var opening: Opening? = null
    /** The next entry opened ahead, waiting for [decoding] to end. */
    private var prefetched: Opening? = null
    private var status = Status.STOPPED
    private var failures = 0
    private var lastLiveCheck = 0L
    private val covers = LinkedHashMap<Long, ByteArray?>()
    private var dirty = false
    private var lastSave = 0L

    init {
        restore()
        thread.start()
    }

    // ---- commands (any thread) --------------------------------------------------------------------------------

    /** Replaces the queue with [tracks] and plays from [start]. */
    fun play(tracks: List<Track>, start: Int = 0) = command {
        queue.clear()
        queue += tracks.map(::entry)
        if (queue.isEmpty()) {
            heard = null
            stopPlayback()
        } else if (state.shuffle && queue.size > 1) {
            val first = queue.removeAt(start.coerceIn(0, queue.lastIndex))
            queue.shuffle()
            queue.add(0, first)
            jump(0)
        } else {
            jump(start.coerceIn(0, queue.lastIndex))
        }
    }

    /** Adds [tracks] at the end of the queue, or right after the current track ([next]). */
    fun enqueue(tracks: List<Track>, next: Boolean = false) = command {
        val entries = tracks.map(::entry)
        val at = if (next) (index(heard) + 1).coerceIn(0, queue.size) else queue.size
        queue.addAll(at, entries)
        upcomingChanged()
        if (status == Status.STOPPED && heard == null && queue.isNotEmpty()) jump(at)
    }

    /** Plays the queue's entry [entry] now. */
    fun playEntry(entry: Long) = command { index(entry).takeIf { it >= 0 }?.let { jump(it) } }

    fun remove(entry: Long) = command {
        val index = index(entry)
        if (index < 0) return@command
        if (entry == heard) {
            val next = nextIndex(index, manual = true)
            queue.removeAt(index)
            if (next == null || queue.isEmpty()) stopPlayback() else jump(if (next > index) next - 1 else next)
        } else {
            queue.removeAt(index)
            upcomingChanged()
        }
    }

    /** Moves the entry [entry] to position [to] in the queue. */
    fun move(entry: Long, to: Int) = command {
        val index = index(entry)
        if (index < 0) return@command
        val moved = queue.removeAt(index)
        queue.add(to.coerceIn(0, queue.size), moved)
        upcomingChanged()
    }

    fun clearQueue() = command {
        stopPlayback()
        queue.clear()
        heard = null
    }

    fun toggle() = command { if (status == Status.PLAYING || status == Status.LOADING) pauseNow() else resumeNow() }
    fun pause() = command { pauseNow() }
    fun resume() = command { resumeNow() }
    fun stop() = command { stopPlayback() }

    fun next() = command {
        val next = nextIndex(index(heard), manual = true)
        if (next == null) stopPlayback() else jump(next)
    }

    /** Back to the start of the track, or to the previous one in its first three seconds. */
    fun previous() = command {
        val index = index(heard)
        if (positionMillis() > RESTART_MILLIS || index < 0) {
            seekNow(0)
            return@command
        }
        val previous = when {
            index > 0 -> index - 1
            state.repeat == Repeat.ALL && queue.isNotEmpty() -> queue.lastIndex
            else -> 0
        }
        jump(previous)
    }

    fun seek(millis: Long) = command { seekNow(millis) }

    /** The volume slider's position, 0..1. */
    fun volume(value: Float) = command {
        val clamped = value.coerceIn(0f, 1f)
        feed.gain(loudness(clamped))
        publish(state.copy(volume = clamped))
        dirty = true
    }

    fun shuffle(on: Boolean) = command {
        if (on == state.shuffle) return@command
        val start = index(heard) + 1
        if (start in 1 until queue.size) {
            val upcoming = queue.subList(start, queue.size)
            if (on) upcoming.shuffle() else upcoming.sortBy { it.order }
        }
        publish(state.copy(shuffle = on))
        upcomingChanged()
        dirty = true
    }

    fun repeat(mode: Repeat) = command {
        publish(state.copy(repeat = mode))
        upcomingChanged()
        dirty = true
    }

    fun normalize(on: Boolean) = command {
        publish(state.copy(normalize = on))
        dirty = true
    }

    fun folders(folders: List<Path>) = command {
        publish(state.copy(folders = folders.distinct()))
        dirty = true
    }

    /** Where in the current track playback is, in milliseconds. Any thread, cheap. */
    fun positionMillis(): Long {
        val segments = segments
        if (segments.isEmpty()) return 0
        val played = feed.playedFrames()
        val segment = segments.lastOrNull { it.frame <= played } ?: segments.first()
        val position = segment.offsetMillis + (played - segment.frame).coerceAtLeast(0) * 1000 / AudioEngine.MusicFeed.RATE
        return segment.durationMillis?.let { position.coerceAtMost(it) } ?: position
    }

    override fun close() {
        command {
            save()
            running = false
        }
        thread.join(3000)
        openers.shutdownNow()
    }

    private fun command(action: () -> Unit) {
        if (running) commands.add(action)
    }

    // ---- the player thread ------------------------------------------------------------------------------------

    private fun loop() {
        try {
            while (running) {
                val busy = decoding != null && feed.bufferedFrames() < TARGET_FRAMES
                var command = if (busy || opening?.result?.isDone == true || prefetched?.result?.isDone == true) commands.poll()
                else commands.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
                while (command != null) {
                    try {
                        command()
                    } catch (failure: RuntimeException) {
                        System.err.println("Music: command failed: $failure")
                        failure.printStackTrace()
                    }
                    command = commands.poll()
                }
                if (!running) break
                try {
                    pump()
                } catch (failure: RuntimeException) {
                    System.err.println("Music: playback failed: $failure")
                    failure.printStackTrace()
                    stopPlayback(failure.message)
                }
                followFeed()
                if (dirty && System.nanoTime() - lastSave > SAVE_NANOS) save()
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            clearPlayback()
            feed.paused(true)
        }
    }

    /** Opens, decodes and writes ahead while playing. */
    private fun pump() {
        if (status == Status.STOPPED || status == Status.PAUSED && decoding != null) return
        val opening = opening
        if (decoding == null && opening != null) {
            if (!opening.result.isDone) return
            this.opening = null
            startDecoding(opening) ?: return
        }
        val current = decoding ?: return
        // Open the next entry ahead, so it follows at once.
        val remaining = current.opened.track.durationMillis?.let { it - current.decodedMillis }
        if (prefetched == null && remaining != null && remaining < PREFETCH_MILLIS) {
            nextIndex(index(current.entry), manual = false)?.let { next ->
                val entry = queue[next]
                if (entry.track.origin != Origin.FILE) prefetched = open(entry, 0)
            }
        }
        var budget = CHUNKS_PER_PASS
        while (budget-- > 0 && feed.bufferedFrames() < TARGET_FRAMES) {
            val chunk = try {
                current.opened.decoder.read()
            } catch (failure: IOException) {
                System.err.println("Music: ${current.opened.track.title}: ${failure.message}")
                publish(state.copy(error = "${current.opened.track.title}: ${failure.message}"))
                null
            }
            if (chunk == null) {
                trackEnded(current)
                return
            }
            if (current.gain != 1f) applyGain(chunk, current.gain)
            feed.write(chunk)
            written += chunk.size / 2
            current.decodedMillis += chunk.size / 2 * 1000L / AudioEngine.MusicFeed.RATE
        }
        if (current.opened.track.durationMillis == null && System.nanoTime() - lastLiveCheck > LIVE_NANOS) {
            lastLiveCheck = System.nanoTime()
            val live = current.opened.decoder.liveTitle()
            if (live != state.liveTitle && current.entry == heard) publish(state.copy(liveTitle = live))
        }
    }

    /** The opened track starts decoding where [opening] asked; null when it failed (the next is tried). */
    private fun startDecoding(opening: Opening): Decoding? {
        val opened = try {
            opening.result.get()
        } catch (failure: java.util.concurrent.ExecutionException) {
            failed(opening.entry, failure.cause ?: failure)
            return null
        } catch (cancelled: java.util.concurrent.CancellationException) {
            return null
        }
        val index = index(opening.entry)
        if (index < 0) {
            opened.decoder.close()
            return null
        }
        if (opening.offsetMillis > 0) {
            try {
                opened.decoder.seek(opening.offsetMillis)
            } catch (failure: IOException) {
                System.err.println("Music: cannot resume ${opened.track.title} at ${opening.offsetMillis} ms: ${failure.message}")
            }
        }
        // A failure just before stays shown while the track after it plays.
        val error = if (failures > 0) state.error else null
        failures = 0
        // What opening learned (tags, the site's title) replaces what was known.
        val entry = queue[index]
        if (opened.track != entry.track) queue[index] = Entry(entry.id, entry.order, opened.track)
        val tags = opened.decoder.tags
        val gain = if (state.normalize && tags.gainDb != null) {
            val wanted = Decoder.linear(tags.gainDb)
            // The peak keeps a raised track from clipping; untagged peaks allow at most +6 dB.
            minOf(wanted, tags.peak?.takeIf { it > 0f }?.let { 1f / it } ?: 2f)
        } else 1f
        val decoding = Decoding(opening.entry, opened, gain, opening.offsetMillis)
        this.decoding = decoding
        segments = segments + Segment(opening.entry, written, opening.offsetMillis, opened.track.durationMillis)
        endFrame = null
        rememberCover(opening.entry, opened)
        if (status == Status.LOADING) {
            status = Status.PLAYING
            feed.paused(false)
        }
        publishQueue(error)
        return decoding
    }

    /**
     * [entry] could not be opened: the next entry is tried (a few times in a row at most), unless a program it
     * needs is missing, which the next would need too.
     */
    private fun failed(entry: Long, failure: Throwable) {
        val index = index(entry)
        val title = queue.getOrNull(index)?.track?.title ?: "Track"
        val reason = failure.message ?: failure.javaClass.simpleName
        System.err.println("Music: cannot play $title: $reason")
        failures++
        val message = if (failure is MissingTool) reason else "$title: $reason"
        val next = if (failures < MAX_FAILURES && failure !is MissingTool) nextIndex(index, manual = true)?.takeIf { it != index } else null
        if (next == null) {
            if (segments.isEmpty()) stopPlayback(message) else {
                // What is already written plays out, then the player stops.
                endFrame = written
                publish(state.copy(error = message))
            }
            return
        }
        // While nothing plays, the entry being tried is the current one.
        if (segments.isEmpty()) heard = queue[next].id
        opening = open(queue[next], 0)
        publishQueue(error = message)
    }

    private fun trackEnded(current: Decoding) {
        current.opened.decoder.close()
        decoding = null
        val next = nextIndex(index(current.entry), manual = false)
        if (next == null) {
            endFrame = written
            return
        }
        val entry = queue[next]
        val ahead = prefetched?.takeIf { it.entry == entry.id }
        prefetched?.takeIf { it !== ahead }?.let { cancel(it) }
        prefetched = null
        opening = ahead ?: open(entry, 0)
    }

    /** Follows what the feed plays: the heard entry changes at a segment's start, and the end stops the player. */
    private fun followFeed() {
        val segments = segments
        if (segments.isEmpty()) return
        val played = feed.playedFrames()
        val segment = segments.lastOrNull { it.frame <= played } ?: return
        if (segment.entry != heard) {
            heard = segment.entry
            // Segments before the heard one are done with.
            this.segments = segments.dropWhile { it !== segment }
            publishQueue(error = state.error.takeIf { decoding?.entry == heard })
        }
        val end = endFrame
        if (end != null && played >= end && decoding == null && opening == null) stopPlayback(state.error)
    }

    private fun jump(index: Int, offsetMillis: Long = 0) {
        clearPlayback()
        val entry = queue.getOrNull(index) ?: return
        heard = entry.id
        status = Status.LOADING
        failures = 0
        opening = open(entry, offsetMillis)
        publishQueue(error = null)
    }

    private fun seekNow(millis: Long) {
        val current = decoding
        val target = millis.coerceAtLeast(0)
        if (current != null && current.entry == heard) {
            try {
                current.opened.decoder.seek(target)
                feed.flush()
                written = 0
                endFrame = null
                prefetched?.let { cancel(it) }
                prefetched = null
                current.decodedMillis = target
                segments = listOf(Segment(current.entry, 0, target, current.opened.track.durationMillis))
                return
            } catch (failure: IOException) {
                // Not seekable in place (a stream); reopen at the time instead.
            }
        }
        val index = index(heard)
        if (index >= 0) {
            val paused = status == Status.PAUSED
            jump(index, target)
            if (paused) pauseNow()
        }
    }

    private fun pauseNow() {
        if (status == Status.STOPPED) return
        status = Status.PAUSED
        feed.paused(true)
        publishQueue(state.error)
    }

    private fun resumeNow() {
        when {
            status == Status.PAUSED && (decoding != null || opening != null) -> {
                status = if (decoding != null) Status.PLAYING else Status.LOADING
                feed.paused(false)
                publishQueue(state.error)
            }
            status != Status.PLAYING && queue.isNotEmpty() -> jump(index(heard).coerceAtLeast(0))
        }
    }

    private fun stopPlayback(error: String? = null) {
        clearPlayback()
        status = Status.STOPPED
        publishQueue(error)
    }

    /** Drops everything playing or opening (the queue stays). */
    private fun clearPlayback() {
        feed.flush()
        decoding?.opened?.decoder?.close()
        decoding = null
        opening?.let { cancel(it) }
        opening = null
        prefetched?.let { cancel(it) }
        prefetched = null
        segments = emptyList()
        written = 0
        endFrame = null
    }

    /** After the queue changed: a track opened ahead that is no longer next is dropped and the right one opened. */
    private fun upcomingChanged() {
        val heardIndex = index(heard)
        val expected = nextIndex(heardIndex, manual = false)?.let { queue[it].id }
        val current = decoding
        prefetched?.takeIf { it.entry != expected }?.let {
            cancel(it)
            prefetched = null
        }
        if (current != null && current.entry != heard && current.entry != expected) {
            // The audio after the heard track is already written: reopen from where the player is.
            jump(heardIndex, positionMillis())
            return
        }
        if (current == null && opening != null && opening!!.entry != heard && opening!!.entry != expected) {
            cancel(opening!!)
            opening = expected?.let { open(queue[index(it)], 0) }
            if (opening == null) endFrame = written
        }
        publishQueue(state.error)
    }

    /** The entry after [index]: repeat one replays it (not when skipping), repeat all wraps around. */
    private fun nextIndex(index: Int, manual: Boolean): Int? = when {
        queue.isEmpty() -> null
        state.repeat == Repeat.ONE && !manual && index >= 0 -> index
        index + 1 < queue.size -> index + 1
        state.repeat != Repeat.OFF -> 0
        else -> null
    }

    private fun open(entry: Entry, offsetMillis: Long): Opening {
        val cancelled = AtomicBoolean()
        val result = openers.submit<Sources.Opened> {
            val opened = sources.open(entry.track) { cancelled.get() || !running }
            if (cancelled.get()) {
                opened.decoder.close()
                throw IOException("Stopped")
            }
            opened
        }
        return Opening(entry.id, offsetMillis, cancelled, result)
    }

    private fun cancel(opening: Opening) {
        opening.cancelled.set(true)
        // A track already opened is closed here; one still opening closes itself when it sees the flag.
        if (opening.result.isDone) {
            try {
                opening.result.get().decoder.close()
            } catch (failure: Exception) {
                // It failed or was cancelled: nothing to close.
            }
        }
    }

    private fun rememberCover(entry: Long, opened: Sources.Opened) {
        if (covers.containsKey(entry)) return
        val embedded = opened.decoder.cover
        if (embedded != null || opened.track.artwork == null) {
            covers[entry] = embedded
        } else {
            covers[entry] = null
            val url = opened.track.artwork
            openers.submit {
                val bytes = try {
                    sources.web.bytes(url, MAX_ARTWORK_BYTES)
                } catch (failure: IOException) {
                    null
                }
                command {
                    covers[entry] = bytes
                    if (heard == entry) publish(state.copy(cover = bytes))
                }
            }
        }
        while (covers.size > COVERS) covers.remove(covers.keys.first())
    }

    private fun entry(track: Track) = ids.incrementAndGet().let { Entry(it, it, track) }

    private fun index(entry: Long?): Int = if (entry == null) -1 else queue.indexOfFirst { it.id == entry }

    private fun publishQueue(error: String?) {
        val index = index(heard)
        val entry = queue.getOrNull(index)
        val live = state.liveTitle.takeIf { entry != null && entry.id == decoding?.entry && state.entry?.id == entry.id }
        publish(state.copy(queue = queue.toList(), current = index, status = status, error = error,
            cover = entry?.let { covers[it.id] }, liveTitle = live))
        dirty = true
    }

    private fun publish(next: State) {
        state = next
        try {
            onChange(next)
        } catch (failure: RuntimeException) {
            System.err.println("Music: listener failed: $failure")
        }
    }

    // ---- persistence ------------------------------------------------------------------------------------------

    private fun restore() {
        val saved = library.readState()
        val tracks = (saved["queue"] as? List<*>).orEmpty().mapNotNull(Track::fromJson)
        queue += tracks.map(::entry)
        val current = (saved["current"] as? Number)?.toInt()?.takeIf { it in queue.indices } ?: -1
        heard = queue.getOrNull(current)?.id
        val volume = (saved["volume"] as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: State().volume
        val folders = (saved["folders"] as? List<*>)?.mapNotNull { (it as? String)?.let { path -> Path.of(path) } }
            ?: Library.defaultFolders()
        state = State(queue = queue.toList(), current = current, volume = volume,
            shuffle = saved["shuffle"] == true,
            repeat = (saved["repeat"] as? String)?.let { name -> Repeat.entries.firstOrNull { it.name.lowercase() == name } } ?: Repeat.OFF,
            normalize = saved["normalize"] != false, folders = folders)
        feed.gain(loudness(volume))
        feed.paused(true)
    }

    private fun save() {
        val state = state
        library.writeState(linkedMapOf(
            "schema" to 1L,
            "volume" to (state.volume * 1000).roundToInt() / 1000.0,
            "shuffle" to state.shuffle,
            "repeat" to state.repeat.name.lowercase(),
            "normalize" to state.normalize,
            "folders" to state.folders.map { it.toString() },
            "current" to state.current.toLong(),
            "queue" to state.queue.take(MAX_SAVED_QUEUE).map { it.track.toJson() },
        ))
        dirty = false
        lastSave = System.nanoTime()
    }

    companion object {
        /** Written ahead of what is heard: 2 s, enough to ride out a busy disk or a slow network moment. */
        const val TARGET_FRAMES = AudioEngine.MusicFeed.RATE * 2L
        const val PREFETCH_MILLIS = 15_000L
        private const val CHUNKS_PER_PASS = 8
        private const val POLL_MILLIS = 20L
        private const val RESTART_MILLIS = 3_000L
        private const val MAX_FAILURES = 5
        private const val LIVE_NANOS = 2_000_000_000L
        private const val SAVE_NANOS = 2_000_000_000L
        private const val COVERS = 16
        private const val MAX_ARTWORK_BYTES = 4 * 1024 * 1024
        private const val MAX_SAVED_QUEUE = 5000

        /**
         * The gain for slider position [volume]: its cube, the curve PulseAudio uses for its software
         * volume. Loudness is heard on a logarithmic scale, so a linear slider crowds every audible change into its
         * top quarter; with the cube, half way is about -18 dB and a tenth is -60 dB, near silence.
         */
        fun loudness(volume: Float): Float = volume.coerceIn(0f, 1f).let { it * it * it }

        /** [volume]'s gain in decibels, for the slider's label; null at silence. */
        fun decibels(volume: Float): Float? = if (volume <= 0f) null else 60f * kotlin.math.log10(volume.coerceIn(0f, 1f))

        private fun applyGain(samples: ShortArray, gain: Float) {
            for (index in samples.indices) {
                samples[index] = (samples[index] * gain).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }
}
