package com.zillit.desktop.core.appupdate

/**
 * Comparing two version strings without ever throwing.
 *
 * ## Why not `String.compareTo`
 *
 * Lexicographic ordering says `"1.10.0" < "1.9.0"`, because `'1' < '9'`. That is
 * the single defect this file exists to prevent: it is invisible for the first
 * nine releases and then, on the tenth, silently tells every user on 1.10 that
 * 1.9 is newer than what they are running.
 *
 * ## Why it must not throw
 *
 * Both operands come from outside the binary — one from a Remote Config value a
 * human typed into the Firebase console, the other from the `jpackage` bundle's
 * `.cfg`. A `NumberFormatException` on a typo would take the app down over a
 * *notification*, which is an absurd trade. Every unparsable segment degrades to
 * zero, so the worst case is a comparison that says "equal" and a banner that
 * stays quiet.
 *
 * ## The rules
 *
 *  - numeric per segment: `1.10.0` > `1.9.0`;
 *  - missing trailing segments are zero: `1.2` == `1.2.0` == `1.2.0.0`;
 *  - a segment is read up to its first non-digit, so `1.2.0-beta` == `1.2.0`
 *    and a pre-release never counts as newer than the release it precedes;
 *  - a leading `v` is tolerated, because release tags carry one;
 *  - surrounding quotes are ignored — see [normalise];
 *  - anything else is zero: `""`, `"latest"`, `"1..2"` all compare as `0`.
 */
object AppVersions {

    /**
     * The value as the person who typed it meant it.
     *
     * Remote Config's console takes a string, and the KDoc table that told the
     * backend team what to publish showed the example as `"1.2.0"` — so that is
     * what got typed, quotes included. Every Zillit project's template carries
     * `desktop_latest_version` as the seven characters `"1.0.3"` (verified
     * 2026-09-14 against dev, QA and prod). Read raw, the first segment is `"1`,
     * which parses as zero, so `"1.0.3"` compared as `0.0.3` — *older* than
     * every installed build — and the banner never once appeared.
     *
     * Stripped here rather than fixed in the console, because the console will
     * be typed into again. Trims whitespace, then one matching pair of straight
     * or curly quotes (`"…"`, `'…'`, `“…”`, `‘…’`), then whitespace again; a
     * value with no quotes passes through untouched.
     */
    fun normalise(raw: String?): String {
        var value = raw?.trim().orEmpty()
        while (value.length >= 2) {
            val open = value.first()
            val close = value.last()
            val pair = QUOTE_PAIRS.any { (o, c) -> open == o && close == c }
            if (!pair) break
            value = value.substring(1, value.length - 1).trim()
        }
        return value
    }

    /**
     * Negative when [left] is older, zero when equal, positive when newer.
     *
     * Deliberately not implementing `Comparator`: callers should read
     * [isNewer] at the use site rather than a bare `< 0`, which is where sign
     * errors live.
     */
    fun compare(left: String?, right: String?): Int {
        val a = segments(left)
        val b = segments(right)
        val width = maxOf(a.size, b.size)
        for (index in 0 until width) {
            val leftSegment = a.getOrElse(index) { 0L }
            val rightSegment = b.getOrElse(index) { 0L }
            if (leftSegment != rightSegment) return if (leftSegment < rightSegment) -1 else 1
        }
        return 0
    }

    /** True when [candidate] is strictly newer than [installed]. */
    fun isNewer(candidate: String?, installed: String?): Boolean = compare(candidate, installed) > 0

    /** True when [installed] is strictly older than [floor] — the mandatory-update test. */
    fun isBelow(installed: String?, floor: String?): Boolean = compare(installed, floor) < 0

    /**
     * True when a string carries at least one digit worth comparing.
     *
     * `"1.2.0"` yes, `""` and `"TBD"` no. Used to tell "the backend has not
     * filled this key in" from "the backend says 0.0.0", which are the same
     * number and very different facts.
     */
    fun isMeaningful(version: String?): Boolean = version?.any { it.isDigit() } == true

    /**
     * Splits into numeric segments, tolerating everything.
     *
     * `toLongOrNull` rather than `toLong` covers the absurd-but-possible
     * 30-digit segment; that case reads as "enormous", which is the only
     * ordering that makes sense for a number too big to hold.
     */
    private fun segments(raw: String?): List<Long> {
        val trimmed = normalise(raw).removePrefix("v").removePrefix("V")
        if (trimmed.isEmpty()) return emptyList()

        return trimmed.split('.').map { segment ->
            val digits = segment.trimStart().takeWhile { it.isDigit() }
            if (digits.isEmpty()) 0L else digits.toLongOrNull() ?: Long.MAX_VALUE
        }
    }

    /** Straight and typographic pairs — a console on a Mac curls quotes as you type. */
    private val QUOTE_PAIRS = listOf('"' to '"', '\'' to '\'', '\u201C' to '\u201D', '\u2018' to '\u2019')
}
