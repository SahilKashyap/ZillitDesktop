package com.zillit.desktop.core.appupdate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The comparison table.
 *
 * Written as a table rather than as prose tests because the defect this guards
 * — lexicographic ordering, where `"1.10.0" < "1.9.0"` — is invisible for nine
 * releases and then wrong for every one after. A table makes the tenth release
 * a row somebody has already read.
 */
class AppVersionsTest {

    /** left, right, expected sign of `compare(left, right)`. */
    private val table = listOf(
        // The one that motivates the whole file.
        Triple("1.10.0", "1.9.0", 1),
        Triple("1.9.0", "1.10.0", -1),
        Triple("2.0.0", "10.0.0", -1),

        // Equality, including the shapes that are equal without looking it.
        Triple("1.2.0", "1.2.0", 0),
        Triple("1.2", "1.2.0", 0),
        Triple("1.2.0.0", "1.2", 0),
        Triple("v1.2.0", "1.2.0", 0),

        // Suffixes are dropped, so a pre-release never outranks its release.
        Triple("1.2.0-beta", "1.2.0", 0),
        Triple("1.2.0-beta", "1.2.1", -1),
        Triple("1.3.0-rc1", "1.2.9", 1),

        // Ordinary ordering.
        Triple("1.2.1", "1.2.0", 1),
        Triple("2.0.0", "1.99.99", 1),
    )

    @Test
    fun `the comparison table holds`() {
        table.forEach { (left, right, expected) ->
            val actual = AppVersions.compare(left, right)
            assertEquals(
                expected,
                actual.coerceIn(-1, 1),
                "compare($left, $right) was $actual, expected sign $expected",
            )
        }
    }

    /**
     * Junk must never throw and must never claim an ordering.
     *
     * Both operands come from outside the binary — one typed into the Firebase
     * console, one read out of a jpackage `.cfg`. Taking the app down over a
     * typo in a *notification* would be an absurd trade.
     */
    @Test
    fun `junk compares as zero rather than throwing`() {
        listOf(null, "", "   ", "latest", "TBD", "..").forEach { junk ->
            assertEquals(0, AppVersions.compare(junk, junk), "compare($junk, $junk)")
            assertEquals(0, AppVersions.compare(junk, "0.0.0"), "compare($junk, 0.0.0)")
        }
    }

    @Test
    fun `a missing version is never newer than a real one`() {
        assertFalse(AppVersions.isNewer(null, "1.0.0"))
        assertFalse(AppVersions.isNewer("", "1.0.0"))
        assertFalse(AppVersions.isNewer("not a version", "1.0.0"))
    }

    @Test
    fun `an absurdly long segment reads as enormous rather than overflowing`() {
        // 30 digits does not fit in a Long. It must not wrap into a negative.
        assertTrue(AppVersions.isNewer("999999999999999999999999999999.0", "9.0"))
    }

    @Test
    fun `isBelow is the mandatory-update test`() {
        assertTrue(AppVersions.isBelow("1.0.9", "1.1.0"))
        assertFalse(AppVersions.isBelow("1.1.0", "1.1.0"))
        assertFalse(AppVersions.isBelow("1.2.0", "1.1.0"))
    }

    @Test
    fun `isMeaningful separates unfilled keys from a real zero`() {
        assertTrue(AppVersions.isMeaningful("0.0.0"))
        assertTrue(AppVersions.isMeaningful("1.2.0-beta"))
        assertFalse(AppVersions.isMeaningful(null))
        assertFalse(AppVersions.isMeaningful(""))
        assertFalse(AppVersions.isMeaningful("TBD"))
    }
}
