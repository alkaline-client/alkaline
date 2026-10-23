package alkaline.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.SOFTDeviceClock;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Plays 1.8.9's sound events through OpenAL Soft. Callers on any thread (the render thread, the UI thread) only
 * queue requests, which never block; one audio thread owns the OpenAL context, and a decoder thread reads and
 * decodes files, so neither disk nor Vorbis work reaches the frame.
 *
 * <p>Each request names an event, a position, a volume and a pitch, as SoundManager.playSound takes an ISound:
 * the event picks one of its files by weight, the category volume and {@link Attenuation}'s clamps apply, and
 * the sound fades linearly over 16 blocks (more for volumes above 1). Alkaline applies that fade itself, every
 * time the listener moves, with OpenAL's distance model off: the gain is exactly vanilla's, while OpenAL still
 * pans by direction. The listener follows the camera every frame through {@link #listener}, without allocating.
 *
 * <p>Voices form a bounded pool, 28 for whole clips and 4 for streams as vanilla's sound library has; a full pool
 * gives way by {@link VoicePolicy}. Decoded clips stay in OpenAL buffers up to {@link #CACHE_BYTES}, least
 * recently played first out once idle. A resource pack reload ({@link #assets}) and {@link #close} release every
 * source and buffer.
 */
public final class AudioEngine implements AutoCloseable {
    /** Where sounds come from: the layered assets. */
    public interface Assets {
        /** The highest-priority copy of an asset, or null. */
        InputStream open(String path) throws IOException;

        /** Every layer's copy, lowest priority first (vanilla's getAllResources, for sounds.json). */
        List<byte[]> readAll(String path) throws IOException;
    }

    static final int CLIP_VOICES = 28;
    static final int STREAM_VOICES = 4;
    /** Decoded PCM kept in OpenAL buffers; the clips PvP plays most fit several times over. */
    static final long CACHE_BYTES = 48L << 20;
    /** A request still waiting for its file after this long is dropped: a late footstep misleads. */
    private static final long STALE_NANOS = 250_000_000L;
    /** OpenAL Soft's mixing period is the device rate over this: 10 ms rather than its default 21 ms. */
    private static final int REFRESH_HZ = 100;
    private static final int STREAM_BUFFERS = 4;
    /** Events whose files are decoded as soon as the assets load, so their first play does not wait. */
    private static final String[] WARM = {"step.", "dig.", "game.player.", "game.potion.", "random.", "gui."};

    private sealed interface Command permits Play, StopKey, StopAll, Volume, Registry, Decoded, StreamOpened {
    }

    /** A request; {@code key} names a record's jukebox (0 for none), {@code relative} is attenuation NONE. */
    private record Play(String event, double x, double y, double z, float volume, float pitch, boolean relative,
                        long key, long queued) implements Command {
    }

    private record StopKey(long key) implements Command {
    }

    private record StopAll() implements Command {
    }

    private record Volume(SoundCategory category, float value) implements Command {
    }

    private record Registry(SoundEvents events, Assets assets, int generation) implements Command {
    }

    private record Decoded(String path, Ogg.Pcm pcm, int generation, long nanos) implements Command {
    }

    /** A request waiting for its clip, with the file it picked. */
    private record Waiting(Play play, SoundEvents.Sound sound) {
    }

    private record StreamOpened(Voice voice, Ogg.Stream stream, int generation, long serial) implements Command {
    }

    private final ConcurrentLinkedQueue<Command> commands = new ConcurrentLinkedQueue<>();
    /** Decoder work: requests waiting to play go first, warming the cache last. */
    private final LinkedBlockingDeque<Runnable> decodes = new LinkedBlockingDeque<>();
    private Thread thread;
    private Thread decoder;
    private volatile boolean running = true;
    /** False once the device failed to open: requests are ignored rather than queued forever. */
    private volatile boolean available = true;
    private volatile boolean logging;
    private volatile int loadGeneration;

    // Listener, written by the render thread each frame, read by the audio thread (optimistic, no allocation).
    private final StampedLock listenerLock = new StampedLock();
    private double listenerX;
    private double listenerY;
    private double listenerZ;
    private float listenerYaw;
    private float listenerPitch;
    private long listenerVersion;

    // ---- audio-thread state ---------------------------------------------------------------------------------

    private long device;
    private long context;
    private FloatBuffer orientation;
    private SoundEvents events = SoundEvents.empty();
    private Assets assets;
    private int generation;
    private final SplittableRandom random = new SplittableRandom();
    private final float[] volumes = new float[SoundCategory.values().length];
    private final Voice[] clipVoices = new Voice[CLIP_VOICES];
    private final Voice[] streamVoices = new Voice[STREAM_VOICES];
    private final Map<String, Clip> clips = new HashMap<>();
    private long cachedBytes;
    private long startOrder;
    private long seenListener = -1;
    private double atX;
    private double atY;
    private double atZ;
    private final float[] audible = new float[CLIP_VOICES];
    private final long[] started = new long[CLIP_VOICES];
    private final Set<String> reportedMissing = new HashSet<>();

    // Statistics (audio thread writes; describe() reads approximately).
    private final AtomicLong requested = new AtomicLong();
    private volatile long played;
    private volatile long dropped;
    private volatile long missing;
    private volatile long decoded;
    private volatile long decodeNanos;
    private volatile int cachedClips;
    private volatile long cachedBytesShown;
    private volatile long latencyTotal;
    private volatile long latencyMax;
    private volatile long deviceLatencyNanos = -1;
    private volatile String deviceName = "none";

    /** A decoded file in an OpenAL buffer, or one being decoded with the plays waiting for it. */
    private static final class Clip {
        final String path;
        int buffer;
        int bytes;
        boolean failed;
        int users;
        long lastUsed;
        final List<Waiting> waiting = new ArrayList<>(2);

        Clip(String path) {
            this.path = path;
        }

        boolean ready() {
            return buffer != 0;
        }
    }

    /** One OpenAL source of the pool and the sound it plays. */
    private static final class Voice {
        final boolean stream;
        int source;
        boolean busy;
        Clip clip;
        Ogg.Stream decoder;
        final int[] buffers = new int[STREAM_BUFFERS];
        boolean ended;
        /** A stream waits for the decoder thread to open its file; {@link #serial} tells stale opens apart. */
        boolean opening;
        long serial;
        SoundCategory category;
        float requestVolume;
        float entryVolume;
        float range;
        double x;
        double y;
        double z;
        boolean relative;
        long key;
        long order;

        Voice(boolean stream) {
            this.stream = stream;
        }
    }

    private AudioEngine() {
        java.util.Arrays.fill(volumes, 1f);
    }

    /** Opens the default output device on the audio thread; until assets arrive every event is unknown. */
    public static AudioEngine start() {
        AudioEngine engine = new AudioEngine();
        engine.thread = new Thread(engine::run, "alkaline-audio");
        engine.thread.setDaemon(true);
        engine.thread.setPriority(Thread.MAX_PRIORITY);
        engine.decoder = new Thread(engine::decodeLoop, "alkaline-audio-decode");
        engine.decoder.setDaemon(true);
        engine.thread.start();
        engine.decoder.start();
        return engine;
    }

    private void decodeLoop() {
        try {
            while (running) {
                decodes.take().run();
            }
        } catch (InterruptedException closing) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- requests (any thread) ------------------------------------------------------------------------------

    /**
     * Loads the sound events from {@code assets} (all layers' sounds.json) on the decoder thread, then swaps them
     * in: every playing sound stops and every source and buffer of the previous assets is released.
     */
    public void assets(Assets assets) {
        int load = ++loadGeneration;
        decodes.offerFirst(() -> {
            long begin = System.nanoTime();
            List<String> layers = new ArrayList<>();
            try {
                for (byte[] layer : assets.readAll("assets/minecraft/sounds.json")) {
                    String text = new String(layer, StandardCharsets.UTF_8);
                    layers.add(!text.isEmpty() && text.charAt(0) == 0xFEFF ? text.substring(1) : text); // a byte order mark
                }
            } catch (IOException failure) {
                System.err.println("Sounds unavailable: " + failure.getMessage());
            }
            int[] warnings = new int[1];
            SoundEvents parsed = SoundEvents.parse(layers, path -> exists(assets, path), warning -> {
                if (warnings[0]++ < 20) {
                    System.err.println("Sounds: " + warning);
                }
            });
            if (warnings[0] > 20) {
                System.err.println("Sounds: " + (warnings[0] - 20) + " more warnings");
            }
            System.out.printf("Sounds: %d events from %d sounds.json in %.1f ms%n", parsed.size(), layers.size(),
                    (System.nanoTime() - begin) / 1e6);
            post(new Registry(parsed, assets, load));
        });
    }

    private static boolean exists(Assets assets, String path) {
        try (InputStream input = assets.open(path)) {
            return input != null;
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** Plays an event at a position (vanilla's linear attenuation). */
    public void play(String event, double x, double y, double z, float volume, float pitch) {
        request(new Play(event, x, y, z, volume, pitch, false, 0L, System.nanoTime()));
    }

    /** Plays an event at the listener without attenuation (PositionedSoundRecord.create: interface sounds). */
    public void playUi(String event, float volume, float pitch) {
        request(new Play(event, 0, 0, 0, volume, pitch, true, 0L, System.nanoTime()));
    }

    /**
     * Plays an event tied to {@code key} (a jukebox's packed position), replacing whatever that key plays, as
     * RenderGlobal.playRecord keeps one sound per jukebox.
     */
    public void playKeyed(long key, String event, double x, double y, double z, float volume, float pitch) {
        request(new StopKey(key));
        request(new Play(event, x, y, z, volume, pitch, false, key, System.nanoTime()));
    }

    /** Stops what {@code key} plays. */
    public void stopKeyed(long key) {
        request(new StopKey(key));
    }

    /** Stops every sound (Minecraft.loadWorld does on each new world). */
    public void stopAll() {
        request(new StopAll());
    }

    /** A category's volume, 0..1; {@link SoundCategory#MASTER} scales everything. */
    public void volume(SoundCategory category, float value) {
        request(new Volume(category, Math.clamp(value, 0f, 1f)));
    }

    /** Prints each request and how it was served (for --stats runs); printed on the audio thread. */
    public void logging(boolean enabled) {
        logging = enabled;
    }

    /**
     * The listener: eye position and look, as SoundManager.setListener takes them each frame. Allocation-free and
     * never waits on the audio thread.
     */
    public void listener(double x, double y, double z, float yaw, float pitch) {
        long stamp = listenerLock.writeLock();
        listenerX = x;
        listenerY = y;
        listenerZ = z;
        listenerYaw = yaw;
        listenerPitch = pitch;
        listenerVersion++;
        listenerLock.unlockWrite(stamp);
    }

    private void request(Command command) {
        if (available && running) {
            if (command instanceof Play) {
                requested.incrementAndGet();
            }
            post(command);
        }
    }

    private void post(Command command) {
        commands.add(command);
        LockSupport.unpark(thread);
    }

    /** A one-line summary for --stats. */
    public String describe() {
        long served = Math.max(1, played);
        return String.format("Audio: %s, device latency %s; %d requests, %d played, %d dropped (pool full), "
                        + "%d unknown or empty; request to play %.2f ms average, %.2f ms worst; %d decodes, %.2f ms "
                        + "average; %d clips cached, %.1f MiB", deviceName,
                deviceLatencyNanos < 0 ? "unknown" : String.format("%.1f ms", deviceLatencyNanos / 1e6),
                requested.get(), played, dropped, missing, latencyTotal / 1e6 / served, latencyMax / 1e6, decoded,
                decoded == 0 ? 0.0 : decodeNanos / 1e6 / decoded, cachedClips, cachedBytesShown / 1048576.0);
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(thread);
        try {
            thread.join(2_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        decoder.interrupt();
        try {
            decoder.join(1_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        // Decodes that finished after the audio thread left still own native memory.
        for (Command command; (command = commands.poll()) != null; ) {
            discard(command);
        }
    }

    // ---- audio thread ---------------------------------------------------------------------------------------

    private void run() {
        if (!openDevice()) {
            available = false;
            commands.clear();
            return;
        }
        try {
            while (running) {
                for (Command command; running && (command = commands.poll()) != null; ) {
                    apply(command);
                }
                updateListener();
                service();
                LockSupport.parkNanos(anyStream() ? 5_000_000L : 10_000_000L);
            }
        } catch (RuntimeException failure) {
            System.err.println("Audio thread failed: " + failure);
            failure.printStackTrace();
            available = false;
        } finally {
            releaseAll();
            closeDevice();
        }
    }

    private boolean openDevice() {
        try {
            device = ALC10.alcOpenDevice((java.nio.ByteBuffer) null);
            if (device == MemoryUtil.NULL) {
                System.err.println("No audio output device; sounds are off.");
                return false;
            }
            ALCCapabilities deviceCaps = ALC.createCapabilities(device);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer attributes = stack.ints(ALC10.ALC_REFRESH, REFRESH_HZ, 0);
                context = ALC10.alcCreateContext(device, attributes);
            }
            if (context == MemoryUtil.NULL || !ALC10.alcMakeContextCurrent(context)) {
                System.err.println("Cannot create an OpenAL context; sounds are off.");
                closeDevice();
                return false;
            }
            AL.createCapabilities(deviceCaps);
            AL10.alDistanceModel(AL10.AL_NONE);
            orientation = MemoryUtil.memAllocFloat(6);
            createVoices();
            String name = ALC10.alcGetString(device, org.lwjgl.openal.ALC11.ALC_ALL_DEVICES_SPECIFIER);
            deviceName = (name == null ? ALC10.alcGetString(device, ALC10.ALC_DEVICE_SPECIFIER) : name)
                    + " (" + AL10.alGetString(AL10.AL_RENDERER) + " " + AL10.alGetString(AL10.AL_VERSION) + ")";
            if (deviceCaps.ALC_SOFT_device_clock) {
                deviceLatencyNanos = SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_LATENCY_SOFT);
            }
            System.out.println("Audio: " + deviceName + (deviceLatencyNanos >= 0
                    ? String.format(", output latency %.1f ms", deviceLatencyNanos / 1e6) : ""));
            return true;
        } catch (RuntimeException | LinkageError failure) {
            System.err.println("OpenAL unavailable; sounds are off: " + failure);
            closeDevice();
            return false;
        }
    }

    private void createVoices() {
        for (int index = 0; index < CLIP_VOICES; index++) {
            clipVoices[index] = new Voice(false);
            clipVoices[index].source = AL10.alGenSources();
        }
        for (int index = 0; index < STREAM_VOICES; index++) {
            Voice voice = new Voice(true);
            voice.source = AL10.alGenSources();
            for (int buffer = 0; buffer < STREAM_BUFFERS; buffer++) {
                voice.buffers[buffer] = AL10.alGenBuffers();
            }
            streamVoices[index] = voice;
        }
    }

    private void closeDevice() {
        if (context != MemoryUtil.NULL) {
            ALC10.alcMakeContextCurrent(MemoryUtil.NULL);
            ALC10.alcDestroyContext(context);
            context = MemoryUtil.NULL;
        }
        if (device != MemoryUtil.NULL) {
            ALC10.alcCloseDevice(device);
            device = MemoryUtil.NULL;
        }
        if (orientation != null) {
            MemoryUtil.memFree(orientation);
            orientation = null;
        }
    }

    private void apply(Command command) {
        switch (command) {
            case Play play -> play(play);
            case StopKey stop -> {
                for (Voice voice : clipVoices) {
                    if (voice.busy && voice.key == stop.key()) {
                        stop(voice);
                    }
                }
                for (Voice voice : streamVoices) {
                    if (voice.busy && voice.key == stop.key()) {
                        stop(voice);
                    }
                }
                for (Clip clip : clips.values()) {
                    clip.waiting.removeIf(waiting -> waiting.play().key() == stop.key());
                }
            }
            case StopAll ignored -> stopEverything();
            case Volume volume -> applyVolume(volume.category(), volume.value());
            case Registry registry -> {
                if (registry.generation() == loadGeneration) {
                    // A pack reload: nothing of the previous assets may survive, sources included.
                    releaseAll();
                    createVoices();
                    events = registry.events();
                    assets = registry.assets();
                    generation = registry.generation();
                    AL10.alListenerf(AL10.AL_GAIN, volumes[SoundCategory.MASTER.ordinal()]);
                    warm();
                }
            }
            case Decoded decoded -> uploaded(decoded);
            case StreamOpened opened -> streamOpened(opened);
        }
    }

    /** Frees what a command owns when it is not applied. */
    private static void discard(Command command) {
        if (command instanceof Decoded decoded && decoded.pcm() != null) {
            decoded.pcm().free();
        } else if (command instanceof StreamOpened opened && opened.stream() != null) {
            opened.stream().close();
        }
    }

    private void warm() {
        List<SoundEvents.Sound> files = events.files(name -> {
            for (String prefix : WARM) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        });
        for (SoundEvents.Sound sound : files) {
            if (!sound.stream() && !clips.containsKey(sound.path())) {
                Clip clip = new Clip(sound.path());
                clips.put(sound.path(), clip);
                decode(clip, false);
            }
        }
    }

    private void play(Play play) {
        float master = volumes[SoundCategory.MASTER.ordinal()];
        if (master <= 0f) {
            return; // SoundManager.playSound: "master volume was zero"
        }
        SoundEvents.Sound sound = events.pick(play.event(), random);
        if (sound == null) {
            missing++;
            if (reportedMissing.add(play.event()) || logging) {
                System.err.println("Sounds: unknown or empty event " + play.event());
            }
            return;
        }
        SoundCategory category = events.category(play.event());
        float gain = Attenuation.sourceGain(play.volume(), sound.volume(), categoryVolume(category));
        if (gain <= 0f) {
            return; // "volume was zero"
        }
        if (sound.stream()) {
            startStream(play, sound, category);
            return;
        }
        Clip clip = clips.get(sound.path());
        if (clip == null) {
            clip = new Clip(sound.path());
            clips.put(sound.path(), clip);
        }
        if (!clip.ready() && clip.waiting.isEmpty() && !clip.failed) {
            decode(clip, true); // ahead of warming, or again if warming has not reached it
        }
        if (clip.failed) {
            missing++;
            return;
        }
        if (!clip.ready()) {
            clip.waiting.add(new Waiting(play, sound));
            return;
        }
        startClip(play, sound, category, clip);
    }

    private float categoryVolume(SoundCategory category) {
        return category == null || category == SoundCategory.MASTER ? 1f : volumes[category.ordinal()];
    }

    private void decode(Clip clip, boolean urgent) {
        Assets source = assets;
        int load = generation;
        String path = clip.path;
        Runnable work = () -> {
            long begin = System.nanoTime();
            Ogg.Pcm pcm = null;
            try (InputStream input = source.open(path)) {
                if (input == null) {
                    throw new IOException("missing");
                }
                pcm = Ogg.decode(input.readAllBytes());
            } catch (IOException | RuntimeException failure) {
                System.err.println("Sounds: cannot decode " + path + ": " + failure.getMessage());
            }
            post(new Decoded(path, pcm, load, System.nanoTime() - begin));
        };
        if (urgent) {
            decodes.offerFirst(work);
        } else {
            decodes.offerLast(work);
        }
    }

    private void uploaded(Decoded decoded) {
        Clip clip = decoded.generation() == generation ? clips.get(decoded.path()) : null;
        if (clip == null || clip.ready()) {
            discard(decoded);
            return;
        }
        this.decoded++;
        decodeNanos += decoded.nanos();
        if (decoded.pcm() == null) {
            clip.failed = true;
            missing += clip.waiting.size();
            clip.waiting.clear();
            return;
        }
        Ogg.Pcm pcm = decoded.pcm();
        clip.buffer = AL10.alGenBuffers();
        AL10.alBufferData(clip.buffer, pcm.channels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16,
                pcm.samples(), pcm.rate());
        clip.bytes = pcm.bytes();
        cachedBytes += clip.bytes;
        pcm.free();
        long now = System.nanoTime();
        for (Waiting waiting : clip.waiting) {
            if (now - waiting.play().queued() <= STALE_NANOS) {
                startClip(waiting.play(), waiting.sound(), events.category(waiting.play().event()), clip);
            } else {
                dropped++;
                log(waiting.play(), waiting.sound(), "dropped: decoded too late");
            }
        }
        clip.waiting.clear();
        evict();
        cachedClips = clips.size();
        cachedBytesShown = cachedBytes;
    }

    /** Drops idle clips, least recently played first, until the cache fits its cap. */
    private void evict() {
        while (cachedBytes > CACHE_BYTES) {
            Clip oldest = null;
            for (Clip clip : clips.values()) {
                if (clip.ready() && clip.users == 0 && (oldest == null || clip.lastUsed < oldest.lastUsed)) {
                    oldest = clip;
                }
            }
            if (oldest == null) {
                return;
            }
            AL10.alDeleteBuffers(oldest.buffer);
            cachedBytes -= oldest.bytes;
            clips.remove(oldest.path);
        }
    }

    private void startClip(Play play, SoundEvents.Sound sound, SoundCategory category, Clip clip) {
        float gain = Attenuation.sourceGain(play.volume(), sound.volume(), categoryVolume(category));
        float range = Attenuation.range(play.volume());
        float incoming = gain * distanceGain(play.relative(), play.x(), play.y(), play.z(), range);
        Voice voice = freeVoice(clipVoices);
        if (voice == null) {
            int busy = 0;
            for (Voice candidate : clipVoices) {
                audible[busy] = audibleGain(candidate);
                started[busy] = candidate.order;
                busy++;
            }
            int victim = VoicePolicy.victim(audible, started, busy, incoming);
            if (victim < 0) {
                dropped++;
                log(play, sound, "dropped: every voice is louder");
                return;
            }
            voice = clipVoices[victim];
            stop(voice);
        }
        voice.busy = true;
        voice.clip = clip;
        clip.users++;
        clip.lastUsed = System.nanoTime();
        configure(voice, play, category, sound.volume(), range);
        AL10.alSourcei(voice.source, AL10.AL_BUFFER, clip.buffer);
        AL10.alSourcef(voice.source, AL10.AL_PITCH, Attenuation.pitch(play.pitch(), sound.pitch()));
        AL10.alSourcef(voice.source, AL10.AL_GAIN, incoming);
        AL10.alSourcePlay(voice.source);
        served(play, sound, incoming);
    }

    private void configure(Voice voice, Play play, SoundCategory category, float entryVolume, float range) {
        voice.category = category;
        voice.requestVolume = play.volume();
        voice.entryVolume = entryVolume;
        voice.range = range;
        voice.x = play.x();
        voice.y = play.y();
        voice.z = play.z();
        voice.relative = play.relative();
        voice.key = play.key();
        voice.order = startOrder++;
        AL10.alSourcei(voice.source, AL10.AL_SOURCE_RELATIVE, play.relative() ? AL10.AL_TRUE : AL10.AL_FALSE);
        AL10.alSource3f(voice.source, AL10.AL_POSITION, (float) play.x(), (float) play.y(), (float) play.z());
        AL10.alSourcei(voice.source, AL10.AL_LOOPING, AL10.AL_FALSE);
    }

    private void served(Play play, SoundEvents.Sound sound, float gain) {
        played++;
        long latency = System.nanoTime() - play.queued();
        latencyTotal += latency;
        latencyMax = Math.max(latencyMax, latency);
        log(play, sound, String.format("gain %.3f, after %.2f ms", gain, latency / 1e6));
    }

    private void log(Play play, SoundEvents.Sound sound, String outcome) {
        if (logging) {
            System.out.printf("[sound] %s -> %s vol %.2f pitch %.2f %s: %s%n", play.event(),
                    sound.path().substring(sound.path().lastIndexOf("/sounds/") + 8), play.volume(), play.pitch(),
                    play.relative() ? "ui" : String.format("at %.1f %.1f %.1f (%.1f blocks)", play.x(), play.y(),
                            play.z(), distance(play.x(), play.y(), play.z())), outcome);
        }
    }

    private static Voice freeVoice(Voice[] pool) {
        for (Voice voice : pool) {
            if (!voice.busy) {
                return voice;
            }
        }
        return null;
    }

    private float audibleGain(Voice voice) {
        return Attenuation.sourceGain(voice.requestVolume, voice.entryVolume, categoryVolume(voice.category))
                * distanceGain(voice.relative, voice.x, voice.y, voice.z, voice.range);
    }

    private float distanceGain(boolean relative, double x, double y, double z, float range) {
        return relative ? 1f : Attenuation.distanceGain(distance(x, y, z), range);
    }

    private double distance(double x, double y, double z) {
        double dx = x - atX;
        double dy = y - atY;
        double dz = z - atZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void startStream(Play play, SoundEvents.Sound sound, SoundCategory category) {
        Voice voice = freeVoice(streamVoices);
        if (voice == null) {
            // The sound library's choice for streams: the oldest gives way.
            voice = streamVoices[0];
            for (Voice candidate : streamVoices) {
                if (candidate.order < voice.order) {
                    voice = candidate;
                }
            }
            stop(voice);
        }
        voice.busy = true;
        voice.opening = true;
        voice.ended = false;
        voice.serial = startOrder;
        configure(voice, play, category, sound.volume(), Attenuation.range(play.volume()));
        AL10.alSourcef(voice.source, AL10.AL_PITCH, Attenuation.pitch(play.pitch(), sound.pitch()));
        Assets source = assets;
        int load = generation;
        long serial = voice.serial;
        Voice target = voice;
        decodes.offerFirst(() -> {
            Ogg.Stream stream = null;
            try (InputStream input = source.open(sound.path())) {
                if (input != null) {
                    stream = new Ogg.Stream(input.readAllBytes());
                }
            } catch (IOException | RuntimeException failure) {
                System.err.println("Sounds: cannot stream " + sound.path() + ": " + failure.getMessage());
            }
            post(new StreamOpened(target, stream, load, serial));
        });
        served(play, sound, audibleGain(voice));
    }

    private void streamOpened(StreamOpened opened) {
        Voice voice = opened.voice();
        if (opened.generation() != generation || !voice.busy || !voice.opening || voice.serial != opened.serial()
                || opened.stream() == null) {
            discard(opened);
            if (voice.serial == opened.serial() && voice.opening) {
                voice.busy = false;
                voice.opening = false;
            }
            return;
        }
        voice.opening = false;
        voice.decoder = opened.stream();
        int queued = 0;
        for (int buffer : voice.buffers) {
            if (!fill(voice, buffer)) {
                break;
            }
            AL10.alSourceQueueBuffers(voice.source, buffer);
            queued++;
        }
        if (queued == 0) {
            stop(voice);
            return;
        }
        AL10.alSourcef(voice.source, AL10.AL_GAIN, audibleGain(voice));
        AL10.alSourcePlay(voice.source);
    }

    /** Decodes the stream's next chunk into {@code buffer}; false at the end of the file. */
    private boolean fill(Voice voice, int buffer) {
        ShortBuffer chunk = voice.decoder.next();
        if (!chunk.hasRemaining()) {
            voice.ended = true;
            return false;
        }
        AL10.alBufferData(buffer, voice.decoder.channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16,
                chunk, voice.decoder.rate);
        return true;
    }

    private boolean anyStream() {
        for (Voice voice : streamVoices) {
            if (voice != null && voice.busy) {
                return true;
            }
        }
        return false;
    }

    private void stop(Voice voice) {
        if (!voice.busy) {
            return;
        }
        AL10.alSourceStop(voice.source);
        if (voice.stream) {
            // Unqueues every buffer, processed once the source is stopped.
            AL10.alSourcei(voice.source, AL10.AL_BUFFER, 0);
            if (voice.decoder != null) {
                voice.decoder.close();
                voice.decoder = null;
            }
            voice.opening = false;
        } else {
            AL10.alSourcei(voice.source, AL10.AL_BUFFER, 0);
            voice.clip.users--;
            voice.clip.lastUsed = System.nanoTime();
            voice.clip = null;
        }
        voice.busy = false;
        voice.key = 0;
    }

    private void stopEverything() {
        for (Voice voice : clipVoices) {
            stop(voice);
        }
        for (Voice voice : streamVoices) {
            stop(voice);
        }
        for (Clip clip : clips.values()) {
            clip.waiting.clear();
        }
    }

    private void applyVolume(SoundCategory category, float value) {
        volumes[category.ordinal()] = value;
        if (category == SoundCategory.MASTER) {
            AL10.alListenerf(AL10.AL_GAIN, value);
            if (value <= 0f) {
                stopEverything(); // SoundHandler.setSoundLevel
            }
            return;
        }
        // SoundManager.setSoundCategoryVolume: playing sounds follow, and stop at zero.
        for (Voice[] pool : new Voice[][]{clipVoices, streamVoices}) {
            for (Voice voice : pool) {
                if (voice.busy && voice.category == category) {
                    if (value <= 0f) {
                        stop(voice);
                    } else {
                        AL10.alSourcef(voice.source, AL10.AL_GAIN, audibleGain(voice));
                    }
                }
            }
        }
    }

    private void updateListener() {
        long stamp = listenerLock.tryOptimisticRead();
        double x = listenerX;
        double y = listenerY;
        double z = listenerZ;
        float yaw = listenerYaw;
        float pitch = listenerPitch;
        long version = listenerVersion;
        if (!listenerLock.validate(stamp)) {
            stamp = listenerLock.readLock();
            try {
                x = listenerX;
                y = listenerY;
                z = listenerZ;
                yaw = listenerYaw;
                pitch = listenerPitch;
                version = listenerVersion;
            } finally {
                listenerLock.unlockRead(stamp);
            }
        }
        if (version == seenListener) {
            return;
        }
        seenListener = version;
        atX = x;
        atY = y;
        atZ = z;
        // SoundManager.setListener: the look and up vectors from yaw and pitch.
        float yawRadians = (float) Math.toRadians(yaw + 90f);
        float lookCos = (float) Math.cos(Math.toRadians(-pitch));
        float lookSin = (float) Math.sin(Math.toRadians(-pitch));
        float upCos = (float) Math.cos(Math.toRadians(-pitch + 90f));
        float upSin = (float) Math.sin(Math.toRadians(-pitch + 90f));
        float cos = (float) Math.cos(yawRadians);
        float sin = (float) Math.sin(yawRadians);
        AL10.alListener3f(AL10.AL_POSITION, (float) x, (float) y, (float) z);
        orientation.put(0, cos * lookCos).put(1, lookSin).put(2, sin * lookCos)
                .put(3, cos * upCos).put(4, upSin).put(5, sin * upCos);
        AL10.alListenerfv(AL10.AL_ORIENTATION, orientation);
        for (Voice voice : clipVoices) {
            if (voice.busy && !voice.relative) {
                AL10.alSourcef(voice.source, AL10.AL_GAIN, audibleGain(voice));
            }
        }
        for (Voice voice : streamVoices) {
            if (voice.busy && !voice.relative && !voice.opening) {
                AL10.alSourcef(voice.source, AL10.AL_GAIN, audibleGain(voice));
            }
        }
    }

    /** Frees finished voices and keeps streams fed. */
    private void service() {
        for (Voice voice : clipVoices) {
            if (voice.busy && AL10.alGetSourcei(voice.source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                stop(voice);
            }
        }
        for (Voice voice : streamVoices) {
            if (!voice.busy || voice.opening) {
                continue;
            }
            int processed = AL10.alGetSourcei(voice.source, AL10.AL_BUFFERS_PROCESSED);
            while (processed-- > 0) {
                int buffer = AL10.alSourceUnqueueBuffers(voice.source);
                if (!voice.ended && fill(voice, buffer)) {
                    AL10.alSourceQueueBuffers(voice.source, buffer);
                }
            }
            int state = AL10.alGetSourcei(voice.source, AL10.AL_SOURCE_STATE);
            if (state == AL10.AL_STOPPED) {
                if (AL10.alGetSourcei(voice.source, AL10.AL_BUFFERS_QUEUED) > 0) {
                    AL10.alSourcePlay(voice.source); // an underrun: carry on with what is queued
                } else {
                    stop(voice);
                }
            }
        }
    }

    /** Stops everything and deletes every source and buffer (the device and context stay). */
    private void releaseAll() {
        if (context == MemoryUtil.NULL) {
            return;
        }
        for (int index = 0; index < CLIP_VOICES; index++) {
            Voice voice = clipVoices[index];
            if (voice != null) {
                stop(voice);
                AL10.alDeleteSources(voice.source);
                clipVoices[index] = null;
            }
        }
        for (int index = 0; index < STREAM_VOICES; index++) {
            Voice voice = streamVoices[index];
            if (voice != null) {
                stop(voice);
                AL10.alDeleteSources(voice.source);
                for (int buffer : voice.buffers) {
                    AL10.alDeleteBuffers(buffer);
                }
                streamVoices[index] = null;
            }
        }
        for (Clip clip : clips.values()) {
            if (clip.ready()) {
                AL10.alDeleteBuffers(clip.buffer);
            }
        }
        clips.clear();
        cachedBytes = 0;
        cachedClips = 0;
        cachedBytesShown = 0;
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            System.err.println("Audio: OpenAL error " + error + " while releasing");
        }
    }
}
