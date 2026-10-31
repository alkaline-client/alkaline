package alkaline.ui.plugins

import alkaline.client.modules.HudAnchor
import alkaline.client.modules.HudPlacement
import alkaline.config.Json
import alkaline.config.Setting
import alkaline.plugin.Author
import alkaline.plugin.PLUGIN_API_MINIMUM
import alkaline.plugin.PLUGIN_API_VERSION
import alkaline.plugin.PluginInfo
import java.util.Locale

/**
 * A plugin's `alkaline-plugin.json`, read and checked (`docs/plugins/manifest.md` is the format's reference and
 * `docs/plugins/manifest.schema.json` its JSON Schema). Its settings and HUD placements are built as Alkaline
 * settings here, so the player can see and change them before the plugin's code ever runs.
 */
class Manifest internal constructor(
    val id: String,
    val name: String,
    val version: Version,
    val entrypoint: String,
    val api: Int,
    val description: String,
    val authors: List<Author>,
    val license: String?,
    val homepage: String?,
    val source: String?,
    val issues: String?,
    /** A PNG inside the jar. */
    val icon: String?,
    val dependencies: Map<String, VersionRange>,
    val optionalDependencies: Map<String, VersionRange>,
    /** Plugin-message channels announced to the server (REGISTER) when the player joins. */
    val channels: List<String>,
    /** Declared settings, as `settings.<id>` in the plugin's settings file, in declaration order. */
    val settings: List<Setting<*>>,
    val hud: List<HudSpec>,
) {
    /** A declared HUD element and the settings that place it (`hud.<id>.*`). */
    class HudSpec(
        val id: String,
        val title: String,
        val description: String,
        val enabled: Setting.BooleanSetting,
        val placement: HudPlacement,
    )

    val info: PluginInfo
        get() = PluginInfo(id, name, version.toString(), description, authors, license, homepage, source, issues, api)

    /** A declared setting by its manifest id. */
    fun setting(id: String): Setting<*>? = settings.firstOrNull { it.id() == "$SETTINGS_PREFIX$id" }

    companion object {
        const val FILE = "alkaline-plugin.json"
        const val SCHEMA = 1
        internal const val SETTINGS_PREFIX = "settings."

        val ID = Regex("[a-z][a-z0-9_-]{1,63}")
        private val CLASS = Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)+")
        private val KEY = Regex("[a-z][a-z0-9_]{0,31}")
        private val CHANNEL = Regex("[\\x21-\\x7E]{1,20}")
        private val KNOWN = setOf(
            "schema", "id", "name", "version", "entrypoint", "api", "minecraft", "description", "authors", "license",
            "links", "icon", "dependencies", "optionalDependencies", "channels", "settings", "hud",
        )

        /** Reads a manifest, or lists everything wrong with it (each problem names its field). */
        fun parse(text: String): Result {
            val root = try {
                Json.parse(text)
            } catch (failure: Json.JsonException) {
                return Result(null, listOf("$FILE is not valid JSON: ${failure.message}"))
            }
            if (root !is Map<*, *>) return Result(null, listOf("$FILE must be a JSON object."))
            val reader = Reader(root)
            val manifest = reader.read()
            return if (reader.problems.isEmpty()) Result(manifest, emptyList()) else Result(null, reader.problems)
        }
    }

    class Result(val manifest: Manifest?, val problems: List<String>)

    private class Reader(private val root: Map<*, *>) {
        val problems = mutableListOf<String>()

        fun problem(path: String, message: String) {
            if (problems.size < 20) problems += "$path: $message"
        }

        fun string(map: Map<*, *>, key: String, path: String, required: Boolean, max: Int): String? {
            val value = map[key]
            if (value == null) {
                if (required) problem(path, "required.")
                return null
            }
            if (value !is String) {
                problem(path, "must be a string.")
                return null
            }
            val trimmed = value.trim()
            when {
                required && trimmed.isEmpty() -> problem(path, "must not be empty.")
                trimmed.length > max -> problem(path, "must be at most $max characters.")
                else -> return trimmed.ifEmpty { null }
            }
            return null
        }

        fun number(map: Map<*, *>, key: String, path: String): Double? = when (val value = map[key]) {
            null -> null
            is Long -> value.toDouble()
            is Double -> value
            else -> {
                problem(path, "must be a number.")
                null
            }
        }

        fun integer(map: Map<*, *>, key: String, path: String): Long? = when (val value = map[key]) {
            null -> null
            is Long -> value
            else -> {
                problem(path, "must be a whole number.")
                null
            }
        }

        fun url(map: Map<*, *>, key: String, path: String): String? {
            val value = string(map, key, path, false, 512) ?: return null
            if (!value.startsWith("https://") && !value.startsWith("http://") && !value.startsWith("mailto:")) {
                problem(path, "must be an http(s) or mailto: address.")
                return null
            }
            return value
        }

        fun read(): Manifest? {
            for (key in root.keys) {
                val name = key.toString()
                if (name !in KNOWN && !name.startsWith("x-")) {
                    problem(name, "unknown field (custom fields start with \"x-\").")
                }
            }
            when (val schema = root["schema"]) {
                null -> problem("schema", "required (this client reads manifest schema $SCHEMA).")
                !is Long -> problem("schema", "must be a whole number.")
                else -> if (schema != SCHEMA.toLong()) {
                    problem("schema", "is $schema; this client reads manifest schema $SCHEMA.")
                }
            }
            val id = string(root, "id", "id", true, 64)
            if (id != null && !id.matches(ID)) {
                problem("id", "\"$id\" must start with a lowercase letter and use only a-z, 0-9, - and _ " +
                    "(2 to 64 characters).")
            }
            val name = string(root, "name", "name", true, 48)
            val versionText = string(root, "version", "version", true, 64)
            val version = versionText?.let { text ->
                Version.parse(text) ?: null.also {
                    problem("version", "\"$text\" is not a Semantic Version (MAJOR.MINOR.PATCH, such as 1.0.0).")
                }
            }
            val entrypoint = string(root, "entrypoint", "entrypoint", true, 256)
            if (entrypoint != null && !entrypoint.matches(CLASS)) {
                problem("entrypoint", "\"$entrypoint\" is not a fully qualified class name.")
            }
            val api = when (val value = root["api"]) {
                null -> null.also { problem("api", "required.") }
                !is Long -> null.also { problem("api", "must be a whole number.") }
                else -> when {
                    value > PLUGIN_API_VERSION -> null.also {
                        problem("api", "needs plugin API $value; this Alkaline has $PLUGIN_API_VERSION. Update Alkaline.")
                    }
                    value < PLUGIN_API_MINIMUM -> null.also {
                        problem("api", "plugin API $value is no longer supported (oldest: $PLUGIN_API_MINIMUM).")
                    }
                    else -> value.toInt()
                }
            }
            val minecraft = string(root, "minecraft", "minecraft", true, 16)
            if (minecraft != null && minecraft != "1.8.9") {
                problem("minecraft", "is \"$minecraft\"; this Alkaline plays 1.8.9.")
            }
            val description = string(root, "description", "description", false, 280) ?: ""
            val authors = authors()
            val license = string(root, "license", "license", false, 64)
            var homepage: String? = null
            var source: String? = null
            var issues: String? = null
            when (val links = root["links"]) {
                null -> {}
                !is Map<*, *> -> problem("links", "must be an object.")
                else -> {
                    for (key in links.keys) {
                        if (key !in setOf("homepage", "source", "issues")) {
                            problem("links.$key", "unknown link (homepage, source or issues).")
                        }
                    }
                    homepage = url(links, "homepage", "links.homepage")
                    source = url(links, "source", "links.source")
                    issues = url(links, "issues", "links.issues")
                }
            }
            val icon = string(root, "icon", "icon", false, 256)
            if (icon != null && (!icon.endsWith(".png") || icon.startsWith("/") || ".." in icon.split('/'))) {
                problem("icon", "must be a relative path to a .png inside the jar.")
            }
            val dependencies = dependencies("dependencies", id)
            val optional = dependencies("optionalDependencies", id)
            for (both in dependencies.keys.intersect(optional.keys)) {
                problem("optionalDependencies.$both", "is also a required dependency.")
            }
            val channels = channels()
            val category = name ?: id ?: "Plugin"
            val settings = settings(category)
            val hud = hud(category)
            if (problems.isNotEmpty()) return null
            return Manifest(id!!, name!!, version!!, entrypoint!!, api!!, description, authors, license, homepage,
                source, issues, icon, dependencies, optional, channels, settings, hud)
        }

        private fun authors(): List<Author> {
            val value = root["authors"] ?: return emptyList()
            if (value !is List<*>) {
                problem("authors", "must be a list.")
                return emptyList()
            }
            return value.mapIndexedNotNull { index, entry ->
                val path = "authors[$index]"
                when (entry) {
                    is String -> entry.trim().takeIf { it.isNotEmpty() && it.length <= 64 }?.let { Author(it) }
                        ?: null.also { problem(path, "must be a name of 1 to 64 characters.") }
                    is Map<*, *> -> {
                        for (key in entry.keys) {
                            if (key != "name" && key != "contact") problem("$path.$key", "unknown field (name, contact).")
                        }
                        val authorName = string(entry, "name", "$path.name", true, 64)
                        val contact = string(entry, "contact", "$path.contact", false, 256)
                        authorName?.let { Author(it, contact) }
                    }
                    else -> null.also { problem(path, "must be a name or {\"name\", \"contact\"}.") }
                }
            }
        }

        private fun dependencies(key: String, self: String?): Map<String, VersionRange> {
            val value = root[key] ?: return emptyMap()
            if (value !is Map<*, *>) {
                problem(key, "must be an object of plugin ids to version ranges.")
                return emptyMap()
            }
            val result = linkedMapOf<String, VersionRange>()
            for ((dependency, range) in value) {
                val path = "$key.$dependency"
                val dependencyId = dependency.toString()
                if (!dependencyId.matches(ID)) problem(path, "is not a plugin id.")
                if (dependencyId == self) problem(path, "a plugin cannot depend on itself.")
                val parsed = (range as? String)?.let { VersionRange.parse(it) }
                if (parsed == null) {
                    problem(path, "\"$range\" is not a version range (such as \"^1.2.0\" or \">=1.0.0 <2.0.0\").")
                } else {
                    result[dependencyId] = parsed
                }
            }
            return result
        }

        private fun channels(): List<String> {
            val value = root["channels"] ?: return emptyList()
            if (value !is List<*>) {
                problem("channels", "must be a list of channel names.")
                return emptyList()
            }
            return value.mapIndexedNotNull { index, channel ->
                if (channel is String && channel.matches(CHANNEL) && !channel.startsWith("MC|")) channel
                else null.also {
                    problem("channels[$index]", "must be 1 to 20 printable characters and not start with \"MC|\".")
                }
            }.distinct()
        }

        private fun settings(category: String): List<Setting<*>> {
            val value = root["settings"] ?: return emptyList()
            if (value !is List<*>) {
                problem("settings", "must be a list.")
                return emptyList()
            }
            val seen = mutableSetOf<String>()
            return value.mapIndexedNotNull { index, entry ->
                val path = "settings[$index]"
                if (entry !is Map<*, *>) return@mapIndexedNotNull null.also { problem(path, "must be an object.") }
                val key = string(entry, "id", "$path.id", true, 32)
                if (key != null && !key.matches(KEY)) {
                    problem("$path.id", "\"$key\" must be lower_snake_case (a-z, 0-9, _), starting with a letter.")
                }
                if (key != null && !seen.add(key)) problem("$path.id", "\"$key\" is declared twice.")
                val title = string(entry, "title", "$path.title", true, 48)
                val description = string(entry, "description", "$path.description", false, 200) ?: ""
                val type = string(entry, "type", "$path.type", true, 16)
                setting(entry, path, type, "$SETTINGS_PREFIX${key ?: "invalid"}", category, title ?: "", description)
                    ?.takeIf { key != null && key.matches(KEY) && title != null }
            }
        }

        private fun setting(entry: Map<*, *>, path: String, type: String?, id: String, category: String, title: String,
                            description: String): Setting<*>? {
            val allowed = mutableSetOf("id", "type", "title", "description", "default")
            fun fields(vararg extra: String) = allowed.addAll(extra)
            val setting: Setting<*>? = when (type) {
                null -> null
                "boolean" -> (entry["default"] ?: false).let { default ->
                    if (default !is Boolean) null.also { problem("$path.default", "must be true or false.") }
                    else Setting.bool(id, category, title, description, default)
                }
                "integer" -> {
                    fields("min", "max")
                    val min = integer(entry, "min", "$path.min")
                    val max = integer(entry, "max", "$path.max")
                    val default = integer(entry, "default", "$path.default")
                    when {
                        min == null || max == null -> null.also { problem(path, "an integer needs min and max.") }
                        min >= max || min < Int.MIN_VALUE || max > Int.MAX_VALUE ->
                            null.also { problem("$path.max", "must be greater than min (32-bit range).") }
                        default != null && default !in min..max ->
                            null.also { problem("$path.default", "must be between min and max.") }
                        else -> Setting.integer(id, category, title, description, (default ?: min).toInt(), min.toInt(),
                            max.toInt())
                    }
                }
                "decimal" -> {
                    fields("min", "max")
                    val min = number(entry, "min", "$path.min")
                    val max = number(entry, "max", "$path.max")
                    val default = number(entry, "default", "$path.default")
                    when {
                        min == null || max == null -> null.also { problem(path, "a decimal needs min and max.") }
                        min >= max -> null.also { problem("$path.max", "must be greater than min.") }
                        default != null && (default < min || default > max) ->
                            null.also { problem("$path.default", "must be between min and max.") }
                        else -> Setting.decimal(id, category, title, description, (default ?: min).toFloat(),
                            min.toFloat(), max.toFloat())
                    }
                }
                "color" -> {
                    val default = entry["default"] ?: "#FFFFFFFF"
                    if (default !is String || !default.matches(Regex("#[0-9a-fA-F]{8}"))) {
                        null.also { problem("$path.default", "must be \"#AARRGGBB\".") }
                    } else {
                        Setting.color(id, category, title, description, default.substring(1).toLong(16).toInt())
                    }
                }
                "text" -> {
                    fields("maxLength")
                    val max = integer(entry, "maxLength", "$path.maxLength") ?: 256L
                    val default = entry["default"] ?: ""
                    when {
                        max !in 1L..4096L -> null.also { problem("$path.maxLength", "must be 1 to 4096.") }
                        default !is String || default.length > max || '\n' in default ->
                            null.also { problem("$path.default", "must be one line within maxLength.") }
                        else -> Setting.text(id, category, title, description, default, max.toInt())
                    }
                }
                "choice" -> {
                    fields("options")
                    val options = (entry["options"] as? List<*>)?.map { it as? String }
                    val default = entry["default"] ?: options?.firstOrNull()
                    when {
                        options == null || options.isEmpty() || options.size > 16 || options.any {
                            it == null || it.isBlank() || it.length > 32
                        } -> null.also { problem("$path.options", "must list 1 to 16 names of 1 to 32 characters.") }
                        options.distinct().size != options.size ->
                            null.also { problem("$path.options", "must not repeat a name.") }
                        default !is String || default !in options ->
                            null.also { problem("$path.default", "must be one of the options.") }
                        else -> Setting.option(id, category, title, description, options.map { it!! }, default)
                    }
                }
                "key" -> {
                    val default = entry["default"] ?: Setting.KeySetting.NONE
                    if (default !is String || !Setting.KeySetting.valid(default)) {
                        null.also {
                            problem("$path.default", "must be \"none\", \"key:<name>\" (such as \"key:R\") or " +
                                "\"mouse:<left|right|middle|x1|x2>\".")
                        }
                    } else {
                        Setting.key(id, category, title, description, default)
                    }
                }
                else -> null.also {
                    problem("$path.type", "\"$type\" is not a setting type (boolean, integer, decimal, color, text, " +
                        "choice, key).")
                }
            }
            for (key in entry.keys) {
                if (key.toString() !in allowed) problem("$path.$key", "unknown field for a $type setting.")
            }
            return setting
        }

        private fun hud(category: String): List<HudSpec> {
            val value = root["hud"] ?: return emptyList()
            if (value !is List<*>) {
                problem("hud", "must be a list.")
                return emptyList()
            }
            val seen = mutableSetOf<String>()
            return value.mapIndexedNotNull { index, entry ->
                val path = "hud[$index]"
                if (entry !is Map<*, *>) return@mapIndexedNotNull null.also { problem(path, "must be an object.") }
                for (key in entry.keys) {
                    if (key.toString() !in setOf("id", "title", "description", "anchor", "x", "y", "scale", "enabled")) {
                        problem("$path.$key", "unknown field.")
                    }
                }
                val key = string(entry, "id", "$path.id", true, 32)
                if (key != null && !key.matches(KEY)) problem("$path.id", "\"$key\" must be lower_snake_case.")
                if (key != null && !seen.add(key)) problem("$path.id", "\"$key\" is declared twice.")
                val title = string(entry, "title", "$path.title", true, 48)
                val description = string(entry, "description", "$path.description", false, 200) ?: ""
                val anchorName = string(entry, "anchor", "$path.anchor", false, 16) ?: "top_left"
                val anchor = HudAnchor.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == anchorName }
                if (anchor == null) {
                    problem("$path.anchor", "\"$anchorName\" is not an anchor (top_left, top, top_right, left, " +
                        "center, right, bottom_left, bottom, bottom_right).")
                }
                val x = number(entry, "x", "$path.x") ?: 0.0
                val y = number(entry, "y", "$path.y") ?: 0.0
                if (kotlin.math.abs(x) > HudPlacement.MAX_OFFSET || kotlin.math.abs(y) > HudPlacement.MAX_OFFSET) {
                    problem(path, "x and y must be within ±${HudPlacement.MAX_OFFSET.toInt()} dp.")
                }
                val scale = number(entry, "scale", "$path.scale") ?: 1.0
                if (scale < HudPlacement.MIN_SCALE || scale > HudPlacement.MAX_SCALE) {
                    problem("$path.scale", "must be ${HudPlacement.MIN_SCALE} to ${HudPlacement.MAX_SCALE}.")
                }
                val enabled = entry["enabled"] ?: true
                if (enabled !is Boolean) problem("$path.enabled", "must be true or false.")
                if (key == null || !key.matches(KEY) || title == null || anchor == null || enabled !is Boolean ||
                    problems.isNotEmpty()) {
                    return@mapIndexedNotNull null
                }
                val base = "hud.$key"
                HudSpec(
                    key, title, description,
                    Setting.bool("$base.enabled", category, title, description, enabled),
                    HudPlacement(
                        Setting.choice("$base.anchor", category, "$title anchor", "", HudAnchor::class.java, anchor),
                        Setting.decimal("$base.x", category, "$title x", "", x.toFloat(),
                            -HudPlacement.MAX_OFFSET, HudPlacement.MAX_OFFSET),
                        Setting.decimal("$base.y", category, "$title y", "", y.toFloat(),
                            -HudPlacement.MAX_OFFSET, HudPlacement.MAX_OFFSET),
                        Setting.decimal("$base.scale", category, "$title size", "", scale.toFloat(),
                            HudPlacement.MIN_SCALE, HudPlacement.MAX_SCALE),
                    ),
                )
            }
        }
    }
}
