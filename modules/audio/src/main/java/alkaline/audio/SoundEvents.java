package alkaline.audio;

import alkaline.config.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * The sound events of every layer's {@code sounds.json}, as 1.8.9's SoundHandler, SoundListSerializer and
 * SoundEventAccessorComposite build them. Layers apply lowest priority first (vanilla's getAllResources order):
 * an event a later layer names again gains its sounds, unless that layer sets {@code "replace": true}, which
 * starts the event over with the new layer's category. An appended event keeps its first category. A layer that
 * fails to parse or validate is skipped whole, as vanilla logs "Invalid sounds.json" and moves on.
 *
 * <p>An entry is a file ({@code sounds/<name>.ogg}, skipped when no layer has it) or, with {@code "type":
 * "event"}, another event, resolved when played so a replaced event is followed. Entries carry a weight (default
 * 1) for the random pick, a volume and pitch (default 1) that scale the request, and the {@code stream} flag.
 *
 * <p>Immutable once built, so the audio thread reads it while a reload builds the next one.
 */
public final class SoundEvents {
    /** sounds.json's own domain; events and files elsewhere carry a {@code domain:} prefix. */
    private static final String DOMAIN = "minecraft";
    /** Nested event references deeper than this resolve to nothing (vanilla would overflow the stack). */
    private static final int MAX_DEPTH = 8;

    /** A file a sound event resolved to, with the entry's volume, pitch and stream flag. */
    public record Sound(String path, float volume, float pitch, boolean stream) {
    }

    private sealed interface Entry permits FileEntry, EventEntry {
    }

    private record FileEntry(Sound sound, int weight) implements Entry {
    }

    private record EventEntry(String event) implements Entry {
    }

    private record Event(SoundCategory category, List<Entry> entries) {
    }

    private final Map<String, Event> events;

    private SoundEvents(Map<String, Event> events) {
        this.events = events;
    }

    public static SoundEvents empty() {
        return new SoundEvents(Map.of());
    }

    /**
     * Builds the events from each layer's sounds.json text, lowest priority first. {@code exists} answers whether
     * an asset path (such as {@code assets/minecraft/sounds/random/click.ogg}) is present in any layer.
     */
    public static SoundEvents parse(List<String> layers, Predicate<String> exists, Consumer<String> warnings) {
        Map<String, Event> events = new HashMap<>();
        for (String layer : layers) {
            Map<String, List<Object>> parsed;
            try {
                parsed = parseLayer(layer);
            } catch (RuntimeException invalid) {
                warnings.accept("Invalid sounds.json: " + invalid.getMessage());
                continue;
            }
            for (Map.Entry<String, List<Object>> list : parsed.entrySet()) {
                String name = list.getKey();
                @SuppressWarnings("unchecked")
                Map<String, Object> header = (Map<String, Object>) list.getValue().getFirst();
                Event event = events.get(name);
                if (event == null || Boolean.TRUE.equals(header.get("replace"))) {
                    event = new Event((SoundCategory) header.get("category"), new ArrayList<>());
                    events.put(name, event);
                }
                for (Object raw : list.getValue().subList(1, list.getValue().size())) {
                    Entry entry = entry(name, raw, exists, warnings);
                    if (entry != null) {
                        event.entries().add(entry);
                    }
                }
            }
        }
        Map<String, Event> frozen = new HashMap<>();
        events.forEach((name, event) -> frozen.put(name, new Event(event.category(), List.copyOf(event.entries()))));
        return new SoundEvents(Map.copyOf(frozen));
    }

    /**
     * SoundListSerializer over one file: each event as its header (replace, category) followed by its raw
     * entries. Throws on anything vanilla's Validate calls or JsonUtils reject, failing the whole layer.
     */
    private static Map<String, List<Object>> parseLayer(String text) {
        if (!(Json.parse(text) instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("not an object");
        }
        Map<String, List<Object>> parsed = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> pair : root.entrySet()) {
            String name = (String) pair.getKey();
            if (!(pair.getValue() instanceof Map<?, ?> object)) {
                throw new IllegalArgumentException(name + " is not an object");
            }
            Object categoryName = object.containsKey("category") ? object.get("category") : SoundCategory.MASTER.jsonName();
            SoundCategory category = categoryName instanceof String named ? SoundCategory.byJsonName(named) : null;
            if (category == null) {
                throw new IllegalArgumentException("Invalid category " + categoryName + " in " + name);
            }
            Map<String, Object> header = new HashMap<>();
            header.put("category", category);
            header.put("replace", bool(object, "replace", false, name));
            List<Object> list = new ArrayList<>();
            list.add(header);
            if (object.containsKey("sounds")) {
                if (!(object.get("sounds") instanceof List<?> sounds)) {
                    throw new IllegalArgumentException(name + ".sounds is not an array");
                }
                for (Object sound : sounds) {
                    list.add(validated(name, sound));
                }
            }
            parsed.put(stripDomain(name), list);
        }
        return parsed;
    }

    /** One entry checked as SoundListSerializer checks it: a name, a known type, positive volume, pitch and weight. */
    private static Object validated(String event, Object sound) {
        if (sound instanceof String) {
            return sound;
        }
        if (!(sound instanceof Map<?, ?> object) || !(object.get("name") instanceof String)) {
            throw new IllegalArgumentException("Invalid sound in " + event);
        }
        if (object.containsKey("type") && !"file".equals(object.get("type")) && !"event".equals(object.get("type"))) {
            throw new IllegalArgumentException("Invalid type " + object.get("type") + " in " + event);
        }
        for (String key : new String[]{"volume", "pitch", "weight"}) {
            if (object.containsKey(key) && !(number(object, key, event) > 0)) {
                throw new IllegalArgumentException("Invalid " + key + " in " + event);
            }
        }
        if (object.containsKey("weight") && !(object.get("weight") instanceof Long)) {
            throw new IllegalArgumentException("Invalid weight in " + event);
        }
        bool(object, "stream", false, event);
        return object;
    }

    private static Entry entry(String event, Object raw, Predicate<String> exists, Consumer<String> warnings) {
        String name = raw instanceof String text ? text : (String) ((Map<?, ?>) raw).get("name");
        if (raw instanceof Map<?, ?> object && "event".equals(object.get("type"))) {
            return new EventEntry(stripDomain(name));
        }
        int colon = name.indexOf(':');
        String domain = colon >= 0 ? name.substring(0, colon) : domainOf(event);
        String path = "assets/" + domain + "/sounds/" + name.substring(colon + 1) + ".ogg";
        if (!exists.test(path)) {
            warnings.accept("File " + path + " does not exist, cannot add it to event " + event);
            return null;
        }
        if (!(raw instanceof Map<?, ?> object)) {
            return new FileEntry(new Sound(path, 1f, 1f, false), 1);
        }
        float volume = object.containsKey("volume") ? (float) number(object, "volume", event) : 1f;
        float pitch = object.containsKey("pitch") ? (float) number(object, "pitch", event) : 1f;
        int weight = object.containsKey("weight") ? (int) (long) (Long) object.get("weight") : 1;
        return new FileEntry(new Sound(path, volume, pitch, bool(object, "stream", false, event)), weight);
    }

    private static double number(Map<?, ?> object, String key, String event) {
        if (!(object.get(key) instanceof Number value)) {
            throw new IllegalArgumentException(key + " in " + event + " is not a number");
        }
        return value.doubleValue();
    }

    private static boolean bool(Map<?, ?> object, String key, boolean fallback, String event) {
        if (!object.containsKey(key)) {
            return fallback;
        }
        if (!(object.get(key) instanceof Boolean value)) {
            throw new IllegalArgumentException(key + " in " + event + " is not a boolean");
        }
        return value;
    }

    /** An event name as the registry keys it: minecraft's events plain, others as {@code domain:name}. */
    private static String stripDomain(String name) {
        return name.startsWith(DOMAIN + ":") ? name.substring(DOMAIN.length() + 1) : name;
    }

    private static String domainOf(String event) {
        int colon = event.indexOf(':');
        return colon >= 0 ? event.substring(0, colon) : DOMAIN;
    }

    /** Whether an event (by its sounds.json name, with or without {@code minecraft:}) is defined. */
    public boolean contains(String event) {
        return events.containsKey(stripDomain(event));
    }

    /** The event's category, or null for an unknown event. */
    public SoundCategory category(String event) {
        Event found = events.get(stripDomain(event));
        return found == null ? null : found.category();
    }

    /** Number of events defined. */
    public int size() {
        return events.size();
    }

    /**
     * SoundEventAccessorComposite.cloneEntry: one sound of the event, drawn by weight; null when the event is
     * unknown or resolves to no file (vanilla's missing_sound).
     */
    public Sound pick(String event, RandomGenerator random) {
        return pick(events.get(stripDomain(event)), random, 0);
    }

    private Sound pick(Event event, RandomGenerator random, int depth) {
        if (event == null || depth > MAX_DEPTH) {
            return null;
        }
        int total = weight(event, depth);
        if (total <= 0) {
            return null;
        }
        int ticket = random.nextInt(total);
        for (Entry entry : event.entries()) {
            ticket -= weight(entry, depth);
            if (ticket < 0) {
                return switch (entry) {
                    case FileEntry file -> file.sound();
                    case EventEntry reference -> pick(events.get(reference.event()), random, depth + 1);
                };
            }
        }
        return null;
    }

    private int weight(Event event, int depth) {
        int total = 0;
        for (Entry entry : event.entries()) {
            total += weight(entry, depth);
        }
        return total;
    }

    /** A file's weight, or the referenced event's total weight (zero when unknown), as vanilla's accessors answer. */
    private int weight(Entry entry, int depth) {
        return switch (entry) {
            case FileEntry file -> file.weight();
            case EventEntry reference -> depth >= MAX_DEPTH ? 0
                    : events.containsKey(reference.event()) ? weight(events.get(reference.event()), depth + 1) : 0;
        };
    }

    /** Every file a set of events can play (for warming the cache), without event references' duplicates. */
    public List<Sound> files(Predicate<String> eventFilter) {
        Map<String, Sound> files = new java.util.LinkedHashMap<>();
        events.forEach((name, event) -> {
            if (eventFilter.test(name)) {
                for (Entry entry : event.entries()) {
                    if (entry instanceof FileEntry file) {
                        files.putIfAbsent(file.sound().path(), file.sound());
                    }
                }
            }
        });
        return List.copyOf(files.values());
    }
}
