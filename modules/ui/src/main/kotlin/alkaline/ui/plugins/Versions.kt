package alkaline.ui.plugins

/**
 * A Semantic Versioning 2.0.0 version (https://semver.org): `MAJOR.MINOR.PATCH`, an optional `-pre.release`,
 * and optional `+build` metadata, which precedence ignores.
 */
class Version private constructor(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val prerelease: List<String>,
    private val text: String,
) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // A pre-release precedes its release; identifiers compare numerically when both are numbers.
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return compareValues(other.prerelease.isEmpty(), prerelease.isEmpty()).let { -it }
        }
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val a = prerelease[index]
            val b = other.prerelease[index]
            val numberA = a.toLongOrNull()
            val numberB = b.toLongOrNull()
            val order = when {
                numberA != null && numberB != null -> compareValues(numberA, numberB)
                numberA != null -> -1
                numberB != null -> 1
                else -> a.compareTo(b)
            }
            if (order != 0) return order
        }
        return compareValues(prerelease.size, other.prerelease.size)
    }

    override fun equals(other: Any?): Boolean = other is Version && compareTo(other) == 0
    override fun hashCode(): Int = listOf(major, minor, patch, prerelease).hashCode()
    override fun toString(): String = text

    companion object {
        private val PATTERN = Regex(
            "(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
                "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?",
        )

        fun parse(text: String): Version? {
            val match = PATTERN.matchEntire(text) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return Version(
                major.toLongOrNull() ?: return null,
                minor.toLongOrNull() ?: return null,
                patch.toLongOrNull() ?: return null,
                if (pre.isEmpty()) emptyList() else pre.split('.'),
                text,
            )
        }
    }
}

/**
 * Which versions of a dependency a plugin accepts, in the npm style most plugin authors know: comparators joined
 * by spaces must all hold, and alternatives are joined by `||`. A comparator is `*`, a version (exactly it),
 * `>`, `>=`, `<`, `<=` or `=` before a version, `^1.2.3` (the same major version, or the same minor below 1.0),
 * or `~1.2.3` (the same minor version). `^` and `~` accept partial versions (`^1`, `~1.2`).
 *
 * A pre-release version matches only a comparator that names a pre-release of the same major.minor.patch, so
 * `>=1.0.0` does not pull in `2.0.0-beta`.
 */
class VersionRange private constructor(private val alternatives: List<List<Comparator>>, private val text: String) {
    private class Comparator(val operator: String, val version: Version)

    fun matches(version: Version): Boolean = alternatives.any { set ->
        set.all { it.holds(version) } && (version.prerelease.isEmpty() || set.any {
            it.version.prerelease.isNotEmpty() && it.version.major == version.major &&
                it.version.minor == version.minor && it.version.patch == version.patch
        })
    }

    private fun Comparator.holds(candidate: Version): Boolean = when (operator) {
        "=" -> candidate == version
        ">" -> candidate > version
        ">=" -> candidate >= version
        "<" -> candidate < version
        "<=" -> candidate <= version
        else -> error(operator)
    }

    override fun toString(): String = text

    companion object {
        val ANY: VersionRange = parse("*")!!

        fun parse(text: String): VersionRange? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.length > 256) return null
            val alternatives = trimmed.split("||").map { alternative ->
                val parts = alternative.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.isEmpty()) return null
                parts.flatMap { part -> comparators(part) ?: return null }
            }
            return VersionRange(alternatives, trimmed)
        }

        private fun comparators(part: String): List<Comparator>? {
            if (part == "*") return emptyList()
            val operator = listOf(">=", "<=", ">", "<", "=", "^", "~").firstOrNull { part.startsWith(it) } ?: ""
            val body = part.removePrefix(operator)
            if (operator == "^" || operator == "~") {
                val numbers = body.substringBefore('-').substringBefore('+').split('.')
                if (numbers.size !in 1..3 || numbers.any { it.toLongOrNull() == null }) return null
                val full = Version.parse(numbers.plus(List(3 - numbers.size) { "0" }).joinToString(".") +
                    body.removePrefix(numbers.joinToString("."))) ?: return null
                val (major, minor) = full.major to full.minor
                val upper = when {
                    operator == "~" && numbers.size == 1 -> "${major + 1}.0.0-0"
                    operator == "~" -> "$major.${minor + 1}.0-0"
                    major > 0 || numbers.size == 1 -> "${major + 1}.0.0-0"
                    minor > 0 || numbers.size == 2 -> "0.${minor + 1}.0-0"
                    else -> "0.0.${full.patch + 1}-0"
                }
                return listOf(Comparator(">=", full), Comparator("<", Version.parse(upper)!!))
            }
            val version = Version.parse(body) ?: return null
            return listOf(Comparator(operator.ifEmpty { "=" }, version))
        }
    }
}
