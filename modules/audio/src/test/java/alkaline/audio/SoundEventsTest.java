package alkaline.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Predicate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class SoundEventsTest {
    private static final Predicate<String> ALL_EXIST = path -> true;

    private static SoundEvents parse(String... layers) {
        return SoundEvents.parse(List.of(layers), ALL_EXIST, warning -> { });
    }

    @Test
    void stringEntriesAreFilesWithDefaults() {
        SoundEvents events = parse("{\"step.stone\": {\"category\": \"neutral\", \"sounds\": [\"step/stone1\"]}}");
        SoundEvents.Sound sound = events.pick("step.stone", new SplittableRandom(1));
        assertEquals(new SoundEvents.Sound("assets/minecraft/sounds/step/stone1.ogg", 1f, 1f, false), sound);
        assertEquals(SoundCategory.NEUTRAL, events.category("step.stone"));
    }

    @Test
    void objectEntriesCarryVolumePitchAndStream() {
        SoundEvents events = parse("{\"records.cat\": {\"category\": \"record\", \"sounds\": "
                + "[{\"name\": \"records/cat\", \"stream\": true, \"volume\": 0.5, \"pitch\": 2}]}}");
        SoundEvents.Sound sound = events.pick("minecraft:records.cat", new SplittableRandom(1));
        assertEquals(new SoundEvents.Sound("assets/minecraft/sounds/records/cat.ogg", 0.5f, 2f, true), sound);
        assertEquals(SoundCategory.RECORDS, events.category("records.cat"));
    }

    @Test
    void categoryDefaultsToMaster() {
        SoundEvents events = parse("{\"gui.button.press\": {\"sounds\": [\"random/click\"]}}");
        assertEquals(SoundCategory.MASTER, events.category("gui.button.press"));
    }

    @Test
    void laterLayersAppendUnlessTheyReplace() {
        String vanilla = "{\"a\": {\"category\": \"player\", \"sounds\": [\"x/one\"]},"
                + " \"b\": {\"category\": \"player\", \"sounds\": [\"x/one\"]}}";
        String pack = "{\"a\": {\"category\": \"block\", \"sounds\": [\"x/two\"]},"
                + " \"b\": {\"category\": \"block\", \"replace\": true, \"sounds\": [\"x/two\"]}}";
        SoundEvents events = parse(vanilla, pack);
        // Appended: both files, the first layer's category kept.
        assertEquals(Set.of("assets/minecraft/sounds/x/one.ogg", "assets/minecraft/sounds/x/two.ogg"),
                picks(events, "a", 200));
        assertEquals(SoundCategory.PLAYERS, events.category("a"));
        // Replaced: only the pack's file, with the pack's category.
        assertEquals(Set.of("assets/minecraft/sounds/x/two.ogg"), picks(events, "b", 200));
        assertEquals(SoundCategory.BLOCKS, events.category("b"));
    }

    @Test
    void weightsSkewThePick() {
        SoundEvents events = parse("{\"e\": {\"category\": \"player\", \"sounds\": "
                + "[{\"name\": \"heavy\", \"weight\": 3}, \"light\"]}}");
        SplittableRandom random = new SplittableRandom(7);
        int heavy = 0;
        for (int draw = 0; draw < 40_000; draw++) {
            if (events.pick("e", random).path().endsWith("heavy.ogg")) {
                heavy++;
            }
        }
        assertEquals(0.75, heavy / 40_000.0, 0.01);
    }

    @Test
    void eventEntriesResolveWhenPlayedAndWeighByTheirEvent() {
        // music.game.creative names music.game as one entry worth music.game's total weight.
        SoundEvents events = parse("{\"base\": {\"category\": \"music\", \"sounds\": [\"m/one\", \"m/two\", \"m/three\"]},"
                + " \"wrapper\": {\"category\": \"music\", \"sounds\": [{\"name\": \"base\", \"type\": \"event\"}, \"m/own\"]}}");
        SplittableRandom random = new SplittableRandom(3);
        int own = 0;
        for (int draw = 0; draw < 40_000; draw++) {
            if (events.pick("wrapper", random).path().endsWith("own.ogg")) {
                own++;
            }
        }
        assertEquals(0.25, own / 40_000.0, 0.01);
        // A later layer replacing the target changes what the reference plays.
        SoundEvents replaced = parse("{\"base\": {\"category\": \"music\", \"sounds\": [\"m/one\"]},"
                        + " \"wrapper\": {\"category\": \"music\", \"sounds\": [{\"name\": \"base\", \"type\": \"event\"}]}}",
                "{\"base\": {\"category\": \"music\", \"replace\": true, \"sounds\": [\"m/new\"]}}");
        assertEquals(Set.of("assets/minecraft/sounds/m/new.ogg"), picks(replaced, "wrapper", 50));
    }

    @Test
    void referencesToUnknownOrCyclicEventsPlayNothing() {
        SoundEvents events = parse("{\"dangling\": {\"sounds\": [{\"name\": \"nowhere\", \"type\": \"event\"}]},"
                + " \"loop\": {\"sounds\": [{\"name\": \"loop\", \"type\": \"event\"}]}}");
        assertNull(events.pick("dangling", new SplittableRandom(1)));
        assertNull(events.pick("loop", new SplittableRandom(1)));
        assertNull(events.pick("unknown", new SplittableRandom(1)));
        assertTrue(events.contains("dangling"));
        assertFalse(events.contains("unknown"));
    }

    @Test
    void missingFilesAreLeftOutWithAWarning() {
        List<String> warnings = new ArrayList<>();
        SoundEvents events = SoundEvents.parse(List.of("{\"e\": {\"sounds\": [\"here\", \"gone\"]}}"),
                path -> path.endsWith("here.ogg"), warnings::add);
        assertEquals(Set.of("assets/minecraft/sounds/here.ogg"), picks(events, "e", 50));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("gone.ogg"));
    }

    @Test
    void domainsInFileNamesPickTheirAssets() {
        SoundEvents events = parse("{\"e\": {\"sounds\": [\"mypack:custom/thing\"]}}");
        assertEquals("assets/mypack/sounds/custom/thing.ogg", events.pick("e", new SplittableRandom(1)).path());
    }

    @Test
    void anInvalidLayerIsSkippedWholeAndTheOthersApply() {
        List<String> warnings = new ArrayList<>();
        String good = "{\"e\": {\"category\": \"player\", \"sounds\": [\"ok\"]}}";
        String[] invalid = {
                "{\"e\": {\"category\": \"nonsense\", \"sounds\": [\"bad\"]}}",
                "{\"e\": {\"sounds\": [{\"name\": \"bad\", \"volume\": 0}]}}",
                "{\"e\": {\"sounds\": [{\"name\": \"bad\", \"type\": \"sample\"}]}}",
                "{\"e\": {\"sounds\": [{\"name\": \"bad\", \"weight\": 0}]}}",
                "{\"e\": {\"sounds\": [{\"pitch\": 1}]}}",
                "{\"e\": ",
        };
        for (String layer : invalid) {
            SoundEvents events = SoundEvents.parse(List.of(good, layer), ALL_EXIST, warnings::add);
            assertEquals(Set.of("assets/minecraft/sounds/ok.ogg"), picks(events, "e", 20), layer);
        }
        assertEquals(invalid.length, warnings.size());
    }

    @Test
    void anEventWithoutSoundsResolvesToNothing() {
        SoundEvents events = parse("{\"e\": {\"category\": \"block\"}}");
        assertTrue(events.contains("e"));
        assertNull(events.pick("e", new SplittableRandom(1)));
    }

    /** The real 1.8.9 sounds.json, when the launcher's assets are installed. */
    @Test
    void vanillaSoundsJson() throws Exception {
        Path assets = Path.of(System.getProperty("user.home"), ".minecraft", "assets");
        Path index = assets.resolve("indexes/1.8.json");
        Assumptions.assumeTrue(Files.isRegularFile(index), "no 1.8 asset index installed");
        String text = Files.readString(index);
        int at = text.indexOf("\"minecraft/sounds.json\"");
        int hashAt = text.indexOf("\"hash\"", at);
        int start = text.indexOf('"', text.indexOf(':', hashAt)) + 1;
        String hash = text.substring(start, text.indexOf('"', start));
        Path file = assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
        Assumptions.assumeTrue(Files.isRegularFile(file), "sounds.json object missing");
        Map<String, Integer> warningsByKind = new HashMap<>();
        SoundEvents events = SoundEvents.parse(List.of(Files.readString(file)), ALL_EXIST,
                warning -> warningsByKind.merge(warning, 1, Integer::sum));
        assertTrue(warningsByKind.isEmpty(), warningsByKind.toString());
        assertEquals(246, events.size());
        assertEquals(SoundCategory.NEUTRAL, events.category("step.stone"));
        assertEquals(6, picks(events, "step.stone", 500).size());
        assertEquals(SoundCategory.PLAYERS, events.category("game.player.hurt"));
        assertEquals(Set.of("assets/minecraft/sounds/damage/hit1.ogg", "assets/minecraft/sounds/damage/hit2.ogg",
                "assets/minecraft/sounds/damage/hit3.ogg"), picks(events, "game.player.hurt", 300));
        assertEquals(SoundCategory.MASTER, events.category("gui.button.press"));
        SoundEvents.Sound record = events.pick("records.cat", new SplittableRandom(1));
        assertNotNull(record);
        assertTrue(record.stream());
        assertFalse(events.pick("random.bow", new SplittableRandom(1)).stream());
        // music.game.creative includes music.game by reference.
        assertTrue(picks(events, "music.game.creative", 2000).contains("assets/minecraft/sounds/music/game/calm1.ogg"));
    }

    private static Set<String> picks(SoundEvents events, String event, int draws) {
        SplittableRandom random = new SplittableRandom(11);
        Set<String> seen = new HashSet<>();
        for (int draw = 0; draw < draws; draw++) {
            SoundEvents.Sound sound = events.pick(event, random);
            if (sound != null) {
                seen.add(sound.path());
            }
        }
        return seen;
    }
}
