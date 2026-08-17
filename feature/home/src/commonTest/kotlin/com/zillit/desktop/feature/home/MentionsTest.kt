package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.domain.completeMention
import com.zillit.desktop.feature.home.domain.mentionMatchedIndices
import com.zillit.desktop.feature.home.domain.mentionMatches
import com.zillit.desktop.feature.home.domain.mentionQueryOf
import com.zillit.desktop.feature.home.domain.mentionRangesIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Crew mentions: plain names on the wire, recognised on this client.
 *
 * The rules worth pinning are the refusals — an email address is not a
 * mention, and a stray `@` early in a paragraph must not swallow the tail.
 */
class MentionsTest {

    private val crew = listOf("Aisha Khan", "Aisha", "Sunil k Gautam", "Vidya Pixel")

    // -- the query being typed ---------------------------------------------

    @Test
    fun `an at sign starts a query at a word boundary only`() {
        assertEquals("", mentionQueryOf("@"))
        assertEquals("Ai", mentionQueryOf("call moved — ask @Ai"))
        assertEquals("Aisha K", mentionQueryOf("@Aisha K"))
        assertNull(mentionQueryOf("mail me at sahil@zillit"))
        assertNull(mentionQueryOf("no mention here"))
    }

    @Test
    fun `a long tail after an early at sign is not a query`() {
        val text = "@ " + "x".repeat(40)
        assertNull(mentionQueryOf(text))
    }

    @Test
    fun `matches filter by prefix and everyone answers an empty query`() {
        assertEquals(4, mentionMatches("", crew).size)
        assertEquals(listOf("Aisha Khan", "Aisha"), mentionMatches("ais", crew))
        assertEquals(listOf("Sunil k Gautam"), mentionMatches("sun", crew))
        assertEquals(emptyList(), mentionMatches("zz", crew))
    }

    // -- the fuzzy tiers ---------------------------------------------------

    @Test
    fun `a later word answers by its own start`() {
        assertEquals(listOf("Vidya Pixel"), mentionMatches("pix", crew))
        assertEquals(listOf("Sunil k Gautam"), mentionMatches("gau", crew))
    }

    @Test
    fun `initials answer, skipping a middle one like camel humps`() {
        assertEquals(listOf("Vidya Pixel"), mentionMatches("vp", crew))
        assertEquals(listOf("Sunil k Gautam"), mentionMatches("skg", crew))
        assertEquals(listOf("Sunil k Gautam"), mentionMatches("sg", crew))
    }

    @Test
    fun `initials are anchored on the first word`() {
        // Middle and last initials alone must not answer — `kg` is not how
        // anyone abbreviates Sunil k Gautam.
        assertEquals(emptyList(), mentionMatches("kg", crew))
        // A single letter still answers as a word start, not as a loose initial.
        assertEquals(listOf("Vidya Pixel"), mentionMatches("p", crew))
    }

    @Test
    fun `a substring inside a name answers`() {
        assertEquals(listOf("Vidya Pixel"), mentionMatches("xel", crew))
    }

    @Test
    fun `a dropped letter still finds the name, but only from three letters`() {
        assertEquals(listOf("Vidya Pixel"), mentionMatches("vdya", crew))
        // Two letters scattered would match half the crew — stay silent.
        assertEquals(emptyList(), mentionMatches("vd", crew))
    }

    @Test
    fun `a whole-name start outranks a word start regardless of crew order`() {
        val names = listOf("Kiran Pal", "Pallavi Rao")
        assertEquals(listOf("Pallavi Rao", "Kiran Pal"), mentionMatches("pal", names))
    }

    // -- the recency boost -------------------------------------------------

    @Test
    fun `recency reorders rows inside a tier only`() {
        // Both Aishas answer `ais` by prefix; the recently mentioned one leads.
        assertEquals(
            listOf("Aisha", "Aisha Khan"),
            mentionMatches("ais", crew, recentFirst = listOf("Aisha")),
        )
        // A fuzzier tier never outranks a prefix answer, however recent.
        val names = listOf("Kiran Pal", "Pallavi Rao")
        assertEquals(
            listOf("Pallavi Rao", "Kiran Pal"),
            mentionMatches("pal", names, recentFirst = listOf("Kiran Pal")),
        )
    }

    @Test
    fun `a bare at sign opens on the people mentioned last`() {
        assertEquals(
            listOf("Vidya Pixel", "Sunil k Gautam", "Aisha Khan", "Aisha"),
            mentionMatches("", crew, recentFirst = listOf("Vidya Pixel", "Sunil k Gautam")),
        )
    }

    // -- which letters lit the row -----------------------------------------

    @Test
    fun `each tier lights the letters that made the row appear`() {
        // Prefix and word start: a solid run where the query sits.
        assertEquals(listOf(0, 1, 2), mentionMatchedIndices("ais", "Aisha Khan"))
        assertEquals(listOf(6, 7, 8), mentionMatchedIndices("pix", "Vidya Pixel"))
        // Initials: one letter per word, middle initial skippable.
        assertEquals(listOf(0, 6), mentionMatchedIndices("vp", "Vidya Pixel"))
        assertEquals(listOf(0, 8), mentionMatchedIndices("sg", "Sunil k Gautam"))
        // Substring: the run inside the name.
        assertEquals(listOf(8, 9, 10), mentionMatchedIndices("xel", "Vidya Pixel"))
        // Typo scatter: the greedy in-order path.
        assertEquals(listOf(0, 2, 3, 4), mentionMatchedIndices("vdya", "Vidya Pixel"))
    }

    @Test
    fun `lit letters spell the query, whatever the tier`() {
        for (query in listOf("ais", "pix", "vp", "sg", "xel", "vdya")) {
            for (name in crew) {
                val lit = mentionMatchedIndices(query, name).map { name[it].lowercaseChar() }
                if (lit.isNotEmpty()) {
                    assertEquals(query.toList(), lit, "query=$query name=$name")
                }
            }
        }
    }

    @Test
    fun `no match and no query light nothing`() {
        assertEquals(emptyList(), mentionMatchedIndices("", "Aisha Khan"))
        assertEquals(emptyList(), mentionMatchedIndices("zz", "Aisha Khan"))
    }

    @Test
    fun `completing replaces the trailing token and appends a space`() {
        assertEquals("ask @Aisha Khan ", completeMention("ask @Ai", "Aisha Khan"))
        assertEquals("@Vidya Pixel ", completeMention("@", "Vidya Pixel"))
    }

    // -- highlight ranges --------------------------------------------------

    @Test
    fun `the longest crew name wins at each at sign`() {
        val body = "confirm with @Aisha Khan today"
        val range = mentionRangesIn(body, crew).single()
        assertEquals("@Aisha Khan", body.substring(range))
    }

    @Test
    fun `case does not matter and multiple mentions all mark`() {
        val body = "@aisha khan and @VIDYA PIXEL please"
        val ranges = mentionRangesIn(body, crew)
        assertEquals(2, ranges.size)
        assertEquals("@aisha khan", body.substring(ranges[0]))
        assertEquals("@VIDYA PIXEL", body.substring(ranges[1]))
    }

    @Test
    fun `emails and unknown names stay plain`() {
        assertEquals(emptyList(), mentionRangesIn("mail sahil@zillit.com", crew))
        assertEquals(emptyList(), mentionRangesIn("ask @Nobody Known", crew))
        assertEquals(emptyList(), mentionRangesIn("no at sign at all", crew))
    }
}
