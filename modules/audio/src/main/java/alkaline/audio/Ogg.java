package alkaline.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Ogg Vorbis through stb_vorbis: whole clips decoded at once (on the decoder thread), and streams decoded a chunk
 * at a time (the {@code "stream": true} entries, records and music). PCM is 16-bit interleaved, allocated with
 * {@link MemoryUtil} and owned by whoever receives it.
 */
final class Ogg {
    private Ogg() {
    }

    /** Decoded samples; {@link #free} returns the memory. */
    record Pcm(ShortBuffer samples, int channels, int rate) {
        int bytes() {
            return samples.remaining() * Short.BYTES;
        }

        void free() {
            MemoryUtil.memFree(samples);
        }
    }

    /** Decodes a whole file. */
    static Pcm decode(byte[] file) throws IOException {
        ByteBuffer data = MemoryUtil.memAlloc(file.length).put(file).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long handle = open(data, stack);
            try {
                STBVorbisInfo info = STBVorbis.stb_vorbis_get_info(handle, STBVorbisInfo.malloc(stack));
                int channels = channels(info);
                int frames = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
                ShortBuffer samples = MemoryUtil.memAllocShort(Math.max(1, frames * channels));
                int decoded = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, samples);
                samples.limit(decoded * channels);
                return new Pcm(samples, channels, info.sample_rate());
            } finally {
                STBVorbis.stb_vorbis_close(handle);
            }
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static long open(ByteBuffer data, MemoryStack stack) throws IOException {
        IntBuffer error = stack.mallocInt(1);
        long handle = STBVorbis.stb_vorbis_open_memory(data, error, null);
        if (handle == MemoryUtil.NULL) {
            throw new IOException("not Ogg Vorbis (stb_vorbis error " + error.get(0) + ")");
        }
        return handle;
    }

    private static int channels(STBVorbisInfo info) throws IOException {
        int channels = info.channels();
        if (channels != 1 && channels != 2) {
            throw new IOException(channels + " channels; only mono and stereo play");
        }
        return channels;
    }

    /** A file decoded on demand; the audio thread refills its queued buffers from it. */
    static final class Stream implements AutoCloseable {
        /** Frames per refill: about 93 ms at 44.1 kHz, four queued. */
        static final int CHUNK_FRAMES = 4096;

        private final ByteBuffer data;
        private final long handle;
        final int channels;
        final int rate;
        private final ShortBuffer chunk;

        Stream(byte[] file) throws IOException {
            data = MemoryUtil.memAlloc(file.length).put(file).flip();
            long opened = MemoryUtil.NULL;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                opened = open(data, stack);
                STBVorbisInfo info = STBVorbis.stb_vorbis_get_info(opened, STBVorbisInfo.malloc(stack));
                channels = channels(info);
                rate = info.sample_rate();
            } catch (IOException | RuntimeException failure) {
                if (opened != MemoryUtil.NULL) {
                    STBVorbis.stb_vorbis_close(opened);
                }
                MemoryUtil.memFree(data);
                throw failure;
            }
            handle = opened;
            chunk = MemoryUtil.memAllocShort(CHUNK_FRAMES * channels);
        }

        /** The next chunk (reused between calls), empty at the end of the file. */
        ShortBuffer next() {
            chunk.clear();
            int frames = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, chunk);
            chunk.limit(frames * channels);
            return chunk;
        }

        @Override
        public void close() {
            STBVorbis.stb_vorbis_close(handle);
            MemoryUtil.memFree(data);
            MemoryUtil.memFree(chunk);
        }
    }
}
