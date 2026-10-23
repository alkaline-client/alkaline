package alkaline.clientapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatTextTest {
    private final ChatText chat = ChatText.load(null);

    @Test
    void actionsCoverTheVisibleCharactersOfTheirPart() {
        ChatText.Rich rich = chat.rich("{\"text\":\"Click \",\"color\":\"gray\",\"extra\":[{\"text\":\"here\",\"color\":\"aqua\","
                + "\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/play\"},"
                + "\"hoverEvent\":{\"action\":\"show_text\",\"value\":{\"text\":\"Join\",\"color\":\"green\"}}},"
                + "{\"text\":\" now\"}]}");
        assertEquals("Click here now", rich.text().replaceAll("§.", ""));
        assertEquals(List.of(new ChatText.Action(6, 10, "run_command", "/play", "§r§aJoin", null)), rich.actions());
    }

    @Test
    void childrenInheritEventsUnlessTheySetTheirOwn() {
        ChatText.Rich rich = chat.rich("{\"text\":\"a\",\"insertion\":\"Steve\","
                + "\"clickEvent\":{\"action\":\"suggest_command\",\"value\":\"/msg Steve \"},"
                + "\"extra\":[\"b\",{\"text\":\"c\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/x\"}}]}");
        assertEquals(List.of(
                new ChatText.Action(0, 1, "suggest_command", "/msg Steve ", null, "Steve"),
                new ChatText.Action(1, 2, "suggest_command", "/msg Steve ", null, "Steve"),
                new ChatText.Action(2, 3, "run_command", "/x", null, "Steve")), rich.actions());
    }

    @Test
    void onlyActionsChatAllowsAreKept() {
        assertTrue(chat.rich("{\"text\":\"f\",\"clickEvent\":{\"action\":\"open_file\",\"value\":\"/etc\"}}")
                .actions().isEmpty());
        assertTrue(chat.rich("{\"text\":\"u\",\"clickEvent\":{\"action\":\"open_url\",\"value\":\"file:///etc\"}}")
                .actions().isEmpty());
        ChatText.Action web = chat.rich("{\"text\":\"u\",\"clickEvent\":{\"action\":\"open_url\","
                + "\"value\":\"HTTPS://example.com\"}}").actions().getFirst();
        assertEquals("open_url", web.click());
    }

    @Test
    void translatedArgumentsCarryTheirEvents() {
        ChatText.Rich rich = chat.rich("{\"translate\":\"%s says %s\",\"with\":[{\"text\":\"Steve\","
                + "\"hoverEvent\":{\"action\":\"show_entity\",\"value\":\"{name:\\\"Steve\\\",type:\\\"Player\\\"}\"}},"
                + "\"hi\"]}");
        assertEquals("Steve says hi", rich.text().replaceAll("§.", ""));
        assertEquals(1, rich.actions().size());
        ChatText.Action name = rich.actions().getFirst();
        assertEquals(0, name.start());
        assertEquals(5, name.end());
        assertEquals("Steve\n§7Player", name.hover());
        assertNull(name.click());
    }

    @Test
    void itemHoversShowTheDisplayNameOrTheId() {
        String named = chat.rich("{\"text\":\"[sword]\",\"hoverEvent\":{\"action\":\"show_item\","
                + "\"value\":\"{id:\\\"minecraft:diamond_sword\\\",Count:1b,tag:{display:{Name:\\\"§bExcalibur\\\"}}}\"}}")
                .actions().getFirst().hover();
        assertEquals("§bExcalibur", named);
        String plain = chat.rich("{\"text\":\"[x]\",\"hoverEvent\":{\"action\":\"show_item\","
                + "\"value\":\"{id:\\\"minecraft:stone\\\",Count:1b}\"}}").actions().getFirst().hover();
        assertEquals("stone", plain);
    }

    @Test
    void codesInsideTextDoNotShiftOffsets() {
        assertEquals(3, ChatText.visibleLength("§aab§lc"));
        ChatText.Rich rich = chat.rich("[\"§cred \",{\"text\":\"link\",\"clickEvent\":{\"action\":\"run_command\","
                + "\"value\":\"/a\"}}]");
        assertEquals(new ChatText.Action(4, 8, "run_command", "/a", null, null), rich.actions().getFirst());
    }
}
