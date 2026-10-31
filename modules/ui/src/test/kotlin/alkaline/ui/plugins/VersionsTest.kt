package alkaline.ui.plugins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionsTest {
    private fun v(text: String) = Version.parse(text) ?: error("not a version: $text")
    private fun matches(range: String, version: String) = VersionRange.parse(range)!!.matches(v(version))

    @Test
    fun parsesSemanticVersionsOnly() {
        for (good in listOf("0.0.0", "1.2.3", "10.20.30", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0+build.7", "1.0.0-rc.1+sha.5")) {
            assertNotNull(Version.parse(good), good)
        }
        for (bad in listOf("1", "1.2", "01.2.3", "1.2.3-", "1.2.3-01", "v1.2.3", "1.2.3.4", "")) {
            assertNull(Version.parse(bad), bad)
        }
    }

    @Test
    fun ordersAsSemVerSpecifies() {
        // semver.org, item 11.
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "2.0.0", "2.1.0", "2.1.1")
        assertEquals(ordered, ordered.reversed().map(::v).sorted().map { it.toString() })
        assertEquals(v("1.0.0+a"), v("1.0.0+b"))
    }

    @Test
    fun caretAndTildeRanges() {
        assertTrue(matches("^1.2.3", "1.9.0"))
        assertFalse(matches("^1.2.3", "2.0.0"))
        assertFalse(matches("^1.2.3", "1.2.2"))
        assertTrue(matches("^0.2.3", "0.2.9"))
        assertFalse(matches("^0.2.3", "0.3.0"))
        assertFalse(matches("^0.0.3", "0.0.4"))
        assertTrue(matches("^1", "1.99.0"))
        assertTrue(matches("~1.2.3", "1.2.9"))
        assertFalse(matches("~1.2.3", "1.3.0"))
        assertTrue(matches("~1", "1.5.0"))
    }

    @Test
    fun comparatorsAlternativesAndPrereleases() {
        assertTrue(matches(">=1.0.0 <2.0.0", "1.5.0"))
        assertFalse(matches(">=1.0.0 <2.0.0", "2.0.0"))
        assertTrue(matches("1.0.0 || >=3.0.0", "3.1.0"))
        assertFalse(matches("1.0.0 || >=3.0.0", "2.0.0"))
        assertTrue(matches("*", "4.0.0"))
        assertFalse(matches("*", "4.0.0-beta"))
        assertFalse(matches(">=1.0.0", "2.0.0-beta"))
        assertTrue(matches(">=2.0.0-beta", "2.0.0-beta.2"))
        assertNull(VersionRange.parse(""))
        assertNull(VersionRange.parse(">=x"))
        assertNull(VersionRange.parse("1.0.0 ||"))
    }
}
