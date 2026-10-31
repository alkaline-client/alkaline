package alkaline.ui.plugins

import alkaline.client.modules.HudAnchor
import alkaline.config.Setting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestTest {
    private val minimal = """
        "schema": 1, "id": "demo", "name": "Demo", "version": "1.0.0", "entrypoint": "example.Demo", "api": 1,
        "minecraft": "1.8.9"
    """

    private fun problems(json: String): List<String> = Manifest.parse(json).also {
        assertNull(it.manifest, "expected problems")
    }.problems

    @Test
    fun readsEveryField() {
        val result = Manifest.parse("""{ $minimal,
            "description": "Does things.",
            "authors": ["Ana", { "name": "Bo", "contact": "bo@example.com" }],
            "license": "MIT",
            "links": { "homepage": "https://example.com", "source": "https://example.com/src" },
            "icon": "demo/icon.png",
            "dependencies": { "lib": "^1.2.0" },
            "optionalDependencies": { "extra": ">=0.1.0" },
            "channels": ["demo:sync"],
            "settings": [
                { "id": "on", "type": "boolean", "title": "On", "default": true },
                { "id": "count", "type": "integer", "title": "Count", "min": 1, "max": 9, "default": 3 },
                { "id": "scale", "type": "decimal", "title": "Scale", "min": 0.5, "max": 2 },
                { "id": "tint", "type": "color", "title": "Tint", "default": "#80FF0000" },
                { "id": "label", "type": "text", "title": "Label", "maxLength": 8, "default": "hi" },
                { "id": "mode", "type": "choice", "title": "Mode", "options": ["A", "B"], "default": "B" },
                { "id": "toggle", "type": "key", "title": "Toggle", "default": "key:R" }
            ],
            "hud": [ { "id": "panel", "title": "Panel", "anchor": "bottom_right", "x": -6, "y": -6, "enabled": false } ],
            "x-store": { "anything": true }
        }""")
        assertEquals(emptyList<String>(), result.problems)
        val manifest = result.manifest!!
        assertEquals("1.0.0", manifest.version.toString())
        assertEquals(listOf("Ana", "Bo"), manifest.authors.map { it.name })
        assertEquals("bo@example.com", manifest.authors[1].contact)
        assertTrue(manifest.dependencies.getValue("lib").matches(Version.parse("1.3.0")!!))
        assertEquals(listOf("demo:sync"), manifest.channels)
        assertEquals(7, manifest.settings.size)
        assertEquals(3, (manifest.setting("count") as Setting.IntSetting).defaultValue())
        assertEquals(0.5f, (manifest.setting("scale") as Setting.FloatSetting).defaultValue())
        assertEquals(0x80FF0000.toInt(), (manifest.setting("tint") as Setting.ColorSetting).defaultValue())
        assertEquals(listOf("A", "B"), (manifest.setting("mode") as Setting.OptionSetting).options())
        val hud = manifest.hud.single()
        assertEquals(HudAnchor.BOTTOM_RIGHT, hud.placement.anchor().defaultValue())
        assertEquals(false, hud.enabled.defaultValue())
        assertEquals("hud.panel.x", hud.placement.x().id())
    }

    @Test
    fun namesEachProblemsField() {
        val found = problems("""{ "schema": 1, "id": "Bad Id", "version": "1.0", "api": 1, "minecraft": "1.12.2",
            "authors": [3], "links": { "homepage": "ftp://x", "chat": "https://x" }, "colour": 1 }""")
        for (expected in listOf("colour: unknown field", "id: \"Bad Id\"", "name: required", "version: \"1.0\"",
            "entrypoint: required", "minecraft: is \"1.12.2\"", "authors[0]:", "links.homepage:", "links.chat:")) {
            assertTrue(found.any { it.startsWith(expected) }, "missing \"$expected\" in $found")
        }
    }

    @Test
    fun rejectsNewerApiAndSchema() {
        assertTrue(problems("""{ $minimal }""".replace("\"api\": 1", "\"api\": 99")).single().startsWith("api: needs plugin API 99"))
        assertTrue(problems("""{ $minimal }""".replace("\"schema\": 1", "\"schema\": 2")).single().startsWith("schema: is 2"))
        assertTrue(problems("not json").single().contains("not valid JSON"))
    }

    @Test
    fun checksSettingsAndHud() {
        val found = problems("""{ $minimal, "settings": [
            { "id": "count", "type": "integer", "title": "Count", "min": 5, "max": 1 },
            { "id": "count", "type": "boolean", "title": "Again" },
            { "id": "mode", "type": "choice", "title": "Mode", "options": ["A"], "default": "C" },
            { "id": "k", "type": "key", "title": "Key", "default": "W" },
            { "id": "n", "type": "slider", "title": "N" },
            { "id": "t", "type": "text", "title": "T", "maxLength": 4, "default": "toolong", "unit": "x" }
        ], "hud": [ { "id": "p", "title": "P", "anchor": "middle" } ],
           "dependencies": { "demo": "*", "other": "^^1" } }""")
        for (expected in listOf("settings[0].max:", "settings[1].id: \"count\" is declared twice",
            "settings[2].default:", "settings[3].default:", "settings[4].type:", "settings[5].default:",
            "settings[5].unit:", "hud[0].anchor:", "dependencies.demo: a plugin cannot depend on itself",
            "dependencies.other:")) {
            assertTrue(found.any { it.startsWith(expected) }, "missing \"$expected\" in $found")
        }
    }
}
