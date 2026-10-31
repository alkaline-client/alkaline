package alkaline.music

import alkaline.audio.AudioEngine
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.javacpp.ShortPointer
import java.io.IOException
import kotlin.math.pow

/** What a file or stream says about itself. */
data class Tags(
    val title: String?,
    val artist: String?,
    val album: String?,
    /** Null for a live stream. */
    val durationMillis: Long?,
    /** ReplayGain (or Opus R128) track gain in dB, relative to ReplayGain's -18 LUFS reference. */
    val gainDb: Float?,
    /** The track's peak sample, 1 being full scale; null when untagged. */
    val peak: Float?,
    /** The codec, such as `flac` or `aac`, and the container, such as `mov,mp4,m4a`. */
    val codec: String,
    val container: String,
)

/**
 * One file or stream opened with FFmpeg and decoded to the music feed's format: interleaved 16-bit stereo at
 * [AudioEngine.MusicFeed.RATE]. Every format FFmpeg reads plays: MP3, AAC and ALAC (in MP4, M4A or ADTS), FLAC,
 * Ogg Vorbis and Opus, WebM, WAV and AIFF, WMA, and network streams (HTTP, HLS, Icecast radio).
 *
 * Owned by one thread at a time. [cancelled] is polled by FFmpeg while it waits on the network, so a skip or a
 * closing client never waits for a slow server. Native memory is released by [close], on failures too.
 */
internal class Decoder private constructor(
    private val format: AVFormatContext,
    private val interrupt: AVIOInterruptCB.Callback_Pointer,
    private val stream: AVStream,
    private val codec: AVCodecContext,
    private val resampler: SwrContext,
    val tags: Tags,
    /** Embedded cover art (JPEG or PNG), when the file has some. */
    val cover: ByteArray?,
) : AutoCloseable {
    private val packet: AVPacket = avcodec.av_packet_alloc()
    private val frame: AVFrame = avutil.av_frame_alloc()
    private var output = BytePointer(0L)
    private var outputFrames = 0
    private val outputs = PointerPointer<BytePointer>(1L)
    private var ended = false
    private var drained = false
    /** After a seek, frames ending before this time (stream time base) are dropped. */
    private var skipUntil = Long.MIN_VALUE

    /**
     * The next chunk of samples (about 85 ms or more), or null at the end. Throws [IOException] when the source
     * fails (a broken file, a dropped connection that does not come back).
     */
    fun read(): ShortArray? {
        val gathered = ArrayList<ShortArray>(4)
        var frames = 0
        while (frames < CHUNK_FRAMES) {
            val piece = decodeOnce() ?: break
            if (piece.isNotEmpty()) {
                gathered += piece
                frames += piece.size / 2
            }
        }
        if (frames == 0) return null
        if (gathered.size == 1) return gathered[0]
        val joined = ShortArray(frames * 2)
        var at = 0
        for (piece in gathered) {
            piece.copyInto(joined, at)
            at += piece.size
        }
        return joined
    }

    /** One decode step: samples (possibly none), or null when everything has been returned. */
    private fun decodeOnce(): ShortArray? {
        val received = avcodec.avcodec_receive_frame(codec, frame)
        if (received == 0) {
            try {
                return convert()
            } finally {
                avutil.av_frame_unref(frame)
            }
        }
        if (received == avutil.AVERROR_EOF) {
            if (drained) return null
            drained = true
            return flushResampler()
        }
        if (received != EAGAIN) throw IOException("Cannot decode: ${error(received)}")
        if (ended) {
            avcodec.avcodec_send_packet(codec, null as AVPacket?)
            return ShortArray(0)
        }
        while (true) {
            val read = avformat.av_read_frame(format, packet)
            if (read < 0) {
                if (read != avutil.AVERROR_EOF && read != avutil.AVERROR_EXIT) throw IOException("Cannot read: ${error(read)}")
                if (read == avutil.AVERROR_EXIT) throw IOException("Stopped")
                ended = true
                avcodec.avcodec_send_packet(codec, null as AVPacket?)
                return ShortArray(0)
            }
            try {
                if (packet.stream_index() != stream.index()) continue
                val sent = avcodec.avcodec_send_packet(codec, packet)
                // A damaged packet is skipped, as players do; the stream goes on.
                if (sent < 0 && sent != EAGAIN && sent != avutil.AVERROR_INVALIDDATA) {
                    throw IOException("Cannot decode: ${error(sent)}")
                }
                return ShortArray(0)
            } finally {
                avcodec.av_packet_unref(packet)
            }
        }
    }

    private fun convert(): ShortArray {
        val samples = frame.nb_samples()
        if (skipUntil != Long.MIN_VALUE) {
            val pts = frame.best_effort_timestamp()
            if (pts != avutil.AV_NOPTS_VALUE) {
                val end = pts + avutil.av_rescale_q(samples.toLong(), avutil.av_make_q(1, codec.sample_rate()), stream.time_base())
                if (end <= skipUntil) return ShortArray(0)
            }
            skipUntil = Long.MIN_VALUE
        }
        val capacity = swresample.swr_get_out_samples(resampler, samples)
        ensureOutput(capacity)
        val converted = swresample.swr_convert(resampler, outputs, capacity, frame.extended_data(), samples)
        if (converted < 0) throw IOException("Cannot resample: ${error(converted)}")
        return copyOut(converted)
    }

    private fun flushResampler(): ShortArray {
        val capacity = swresample.swr_get_out_samples(resampler, 0).coerceAtLeast(0)
        if (capacity == 0) return ShortArray(0)
        ensureOutput(capacity)
        val converted = swresample.swr_convert(resampler, outputs, capacity, null as PointerPointer<*>?, 0)
        return if (converted <= 0) ShortArray(0) else copyOut(converted)
    }

    private fun ensureOutput(frames: Int) {
        if (frames <= outputFrames) return
        output.close()
        outputFrames = maxOf(frames, CHUNK_FRAMES)
        output = BytePointer(outputFrames * 4L)
        outputs.put(0L, output)
    }

    private fun copyOut(frames: Int): ShortArray {
        val samples = ShortArray(frames * 2)
        ShortPointer(output).get(samples, 0, samples.size)
        return samples
    }

    /** Moves to [millis] from the start; the next [read] returns samples from there. */
    fun seek(millis: Long) {
        val target = millis.coerceAtLeast(0) * 1000L
        val result = avformat.avformat_seek_file(format, -1, Long.MIN_VALUE, target, target, 0)
        if (result < 0) throw IOException("Cannot seek: ${error(result)}")
        avcodec.avcodec_flush_buffers(codec)
        swresample.swr_init(resampler)
        ended = false
        drained = false
        skipUntil = avutil.av_rescale_q(target, avutil.av_make_q(1, 1_000_000), stream.time_base())
    }

    /** The title an Icecast or Shoutcast radio sends for what it plays now, or null. */
    fun liveTitle(): String? {
        val io = format.pb() ?: return null
        val value = BytePointer(null as Pointer?)
        if (avutil.av_opt_get(io, "icy_metadata_packet", avutil.AV_OPT_SEARCH_CHILDREN, value) < 0 || value.isNull) return null
        return try {
            Regex("StreamTitle='(.*?)';").find(value.string)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        } finally {
            avutil.av_free(value)
        }
    }

    override fun close() {
        avcodec.av_packet_free(packet)
        avutil.av_frame_free(frame)
        output.close()
        outputs.close()
        swresample.swr_free(resampler)
        avcodec.avcodec_free_context(codec)
        avformat.avformat_close_input(format)
        interrupt.close()
    }

    companion object {
        /** Frames per chunk handed to the feed: 4096 at 48 kHz is about 85 ms. */
        const val CHUNK_FRAMES = 4096
        private const val EAGAIN = -11
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"

        init {
            avutil.av_log_set_level(avutil.AV_LOG_FATAL)
            avformat.avformat_network_init()
        }

        /**
         * Opens [location] (a file path or a URL) for playback, sending [headers] with network requests. Throws
         * [IOException] with FFmpeg's reason when it cannot.
         */
        fun open(location: String, headers: Map<String, String> = emptyMap(), cancelled: () -> Boolean = { false }): Decoder =
            openContext(location, headers, cancelled, decode = true).let { it as Decoder }

        /** Reads [location]'s tags and cover without decoding (the library scan). */
        fun probe(location: String, cancelled: () -> Boolean = { false }): Pair<Tags, ByteArray?> {
            @Suppress("UNCHECKED_CAST")
            return openContext(location, emptyMap(), cancelled, decode = false) as Pair<Tags, ByteArray?>
        }

        private fun openContext(location: String, headers: Map<String, String>, cancelled: () -> Boolean, decode: Boolean): Any {
            val format = avformat.avformat_alloc_context() ?: throw IOException("Out of memory")
            val interrupt = object : AVIOInterruptCB.Callback_Pointer() {
                override fun call(opaque: Pointer?): Int = if (cancelled()) 1 else 0
            }
            format.interrupt_callback().callback(interrupt)
            val options = AVDictionary(null as Pointer?)
            val network = location.startsWith("http://") || location.startsWith("https://")
            if (network) {
                avutil.av_dict_set(options, "user_agent", headers["User-Agent"] ?: USER_AGENT, 0)
                val extra = headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
                if (extra.isNotEmpty()) {
                    avutil.av_dict_set(options, "headers", extra.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }, 0)
                }
                avutil.av_dict_set(options, "reconnect", "1", 0)
                avutil.av_dict_set(options, "reconnect_streamed", "1", 0)
                avutil.av_dict_set(options, "reconnect_on_network_error", "1", 0)
                avutil.av_dict_set(options, "reconnect_delay_max", "4", 0)
                avutil.av_dict_set(options, "rw_timeout", "15000000", 0)
                avutil.av_dict_set(options, "icy", "1", 0)
            }
            val opened = avformat.avformat_open_input(format, location, null, options)
            avutil.av_dict_free(options)
            if (opened < 0) {
                // avformat_open_input frees the context on failure.
                interrupt.close()
                throw IOException(if (opened == avutil.AVERROR_EXIT) "Stopped" else error(opened))
            }
            var codec: AVCodecContext? = null
            var resampler: SwrContext? = null
            try {
                val info = avformat.avformat_find_stream_info(format, null as PointerPointer<*>?)
                if (info < 0) throw IOException("Cannot read the stream: ${error(info)}")
                val index = avformat.av_find_best_stream(format, avutil.AVMEDIA_TYPE_AUDIO, -1, -1, null as PointerPointer<*>?, 0)
                if (index < 0) throw IOException("No audio in it")
                val stream = format.streams(index)
                val parameters = stream.codecpar()
                val decoder = avcodec.avcodec_find_decoder(parameters.codec_id())
                    ?: throw IOException("No decoder for its audio")
                val tags = tagsOf(format, stream, decoder.name().string)
                val cover = coverOf(format)
                if (!decode) {
                    avformat.avformat_close_input(format)
                    interrupt.close()
                    return tags to cover
                }
                codec = avcodec.avcodec_alloc_context3(decoder) ?: throw IOException("Out of memory")
                check(avcodec.avcodec_parameters_to_context(codec, parameters) >= 0) { "Bad codec parameters" }
                codec.pkt_timebase(stream.time_base())
                val codecOpened = avcodec.avcodec_open2(codec, decoder, null as PointerPointer<*>?)
                if (codecOpened < 0) throw IOException("Cannot open the decoder: ${error(codecOpened)}")
                val input = AVChannelLayout()
                if (codec.ch_layout().order() == avutil.AV_CHANNEL_ORDER_UNSPEC) {
                    avutil.av_channel_layout_default(input, codec.ch_layout().nb_channels().coerceAtLeast(1))
                } else {
                    avutil.av_channel_layout_copy(input, codec.ch_layout())
                }
                resampler = SwrContext(null as Pointer?)
                val configured = swresample.swr_alloc_set_opts2(resampler, avutil.AV_CHANNEL_LAYOUT_STEREO(),
                    avutil.AV_SAMPLE_FMT_S16, AudioEngine.MusicFeed.RATE, input, codec.sample_fmt(), codec.sample_rate(), 0, null)
                avutil.av_channel_layout_uninit(input)
                if (configured < 0 || resampler.isNull) throw IOException("Cannot resample: ${error(configured)}")
                val initialized = swresample.swr_init(resampler)
                if (initialized < 0) throw IOException("Cannot resample: ${error(initialized)}")
                return Decoder(format, interrupt, stream, codec, resampler, tags, cover)
            } catch (failure: Throwable) {
                resampler?.let { swresample.swr_free(it) }
                codec?.let { avcodec.avcodec_free_context(it) }
                avformat.avformat_close_input(format)
                interrupt.close()
                throw failure
            }
        }

        private fun tagsOf(format: AVFormatContext, stream: AVStream, codec: String): Tags {
            fun tag(vararg keys: String): String? {
                for (key in keys) {
                    for (dictionary in listOf(stream.metadata(), format.metadata())) {
                        if (dictionary == null || dictionary.isNull) continue
                        val entry = avutil.av_dict_get(dictionary, key, null, 0) ?: continue
                        val value = entry.value()?.string?.trim()
                        if (!value.isNullOrEmpty()) return value
                    }
                }
                return null
            }
            val duration = format.duration().takeIf { it != avutil.AV_NOPTS_VALUE && it > 0 }?.let { it / 1000 }
            // ReplayGain 2 tags ("-6.48 dB"); Opus files carry R128 gain in Q7.8 dB relative to -23 LUFS instead.
            val replayGain = tag("REPLAYGAIN_TRACK_GAIN")?.let { Regex("[-+]?[0-9]*\\.?[0-9]+").find(it)?.value?.toFloatOrNull() }
            val r128 = tag("R128_TRACK_GAIN")?.toIntOrNull()?.let { it / 256f + 5f }
            val peak = tag("REPLAYGAIN_TRACK_PEAK")?.let { Regex("[0-9]*\\.?[0-9]+").find(it)?.value?.toFloatOrNull() }
            return Tags(
                title = tag("title"),
                artist = tag("artist", "album_artist", "ARTISTS"),
                album = tag("album"),
                durationMillis = duration,
                gainDb = replayGain ?: r128,
                peak = peak,
                codec = codec,
                container = format.iformat()?.name()?.string ?: "",
            )
        }

        private fun coverOf(format: AVFormatContext): ByteArray? {
            for (index in 0 until format.nb_streams()) {
                val stream = format.streams(index)
                if (stream.disposition() and avformat.AV_DISPOSITION_ATTACHED_PIC == 0) continue
                val picture = stream.attached_pic()
                val size = picture.size()
                if (size <= 0 || size > MAX_COVER_BYTES) continue
                val bytes = ByteArray(size)
                picture.data().get(bytes)
                return bytes
            }
            return null
        }

        private const val MAX_COVER_BYTES = 8 * 1024 * 1024

        fun error(code: Int): String {
            val buffer = ByteArray(256)
            avutil.av_strerror(code, buffer, buffer.size.toLong())
            return String(buffer, 0, buffer.indexOf(0).let { if (it < 0) buffer.size else it }, Charsets.UTF_8)
        }

        /** The linear factor for [gainDb] decibels. */
        fun linear(gainDb: Float): Float = 10f.pow(gainDb / 20f)
    }
}
