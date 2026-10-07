package com.zillit.desktop.feature.selectstills

import com.zillit.desktop.feature.selectstills.domain.Decision
import com.zillit.desktop.feature.selectstills.domain.PhotoFilters
import com.zillit.desktop.feature.selectstills.domain.PhotoKind
import com.zillit.desktop.feature.selectstills.domain.PhotoStatus
import com.zillit.desktop.feature.selectstills.domain.PhotoTile
import com.zillit.desktop.feature.selectstills.domain.PickRefusal
import com.zillit.desktop.feature.selectstills.domain.PublicState
import com.zillit.desktop.feature.selectstills.domain.SectionAllowance
import com.zillit.desktop.feature.selectstills.domain.StillsPick
import com.zillit.desktop.feature.selectstills.domain.StillsSummary
import com.zillit.desktop.feature.selectstills.domain.StillsUrlCache
import com.zillit.desktop.feature.selectstills.domain.allowanceWords
import com.zillit.desktop.feature.selectstills.domain.appendPage
import com.zillit.desktop.feature.selectstills.domain.countFor
import com.zillit.desktop.feature.selectstills.domain.discardBlocked
import com.zillit.desktop.feature.selectstills.domain.discardIsFree
import com.zillit.desktop.feature.selectstills.domain.formatBytes
import com.zillit.desktop.feature.selectstills.domain.idBatches
import com.zillit.desktop.feature.selectstills.domain.limitsFromInputs
import com.zillit.desktop.feature.selectstills.domain.limitsToInputs
import com.zillit.desktop.feature.selectstills.domain.patchList
import com.zillit.desktop.feature.selectstills.domain.removeIds
import com.zillit.desktop.feature.selectstills.domain.screenPicks
import com.zillit.desktop.feature.selectstills.domain.sectionOf
import com.zillit.desktop.feature.selectstills.domain.stillsTypeOf
import com.zillit.desktop.feature.selectstills.domain.toQuery
import com.zillit.desktop.core.strings.S
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The pure logic the web keeps in `lib/`, ported rule for rule. */
class StillsLogicTest {

    // -- allowances --------------------------------------------------------------------

    @Test
    fun `a section with no limit is uncapped, and blank is not zero`() {
        assertEquals(S.desktop_stk_allow_none, allowanceWords(null).key)
        assertEquals(S.desktop_stk_allow_none, allowanceWords(SectionAllowance(limit = null, used = 3)).key)
        // 0 is a real cap: nothing may be discarded there at all.
        val zero = allowanceWords(SectionAllowance(limit = 0, used = 0, remaining = 0))
        assertEquals(S.desktop_stk_allow_zero, zero.key)
        assertTrue(zero.bad)
    }

    @Test
    fun `a count above the cap reads as over, not as none left`() {
        val over = allowanceWords(SectionAllowance(limit = 2, used = 3, remaining = 0))
        assertEquals(S.desktop_stk_allow_over, over.key)
        assertEquals(3, over.used)
        assertEquals(2, over.limit)
        assertTrue(over.bad)

        val spent = allowanceWords(SectionAllowance(limit = 2, used = 2, remaining = 0))
        assertEquals(S.desktop_stk_allow_spent, spent.key)

        val left = allowanceWords(SectionAllowance(limit = 3, used = 1, remaining = 2))
        assertEquals(S.desktop_stk_allow_left, left.key)
        assertEquals(2, left.remaining)
        assertFalse(left.bad)
    }

    @Test
    fun `agreeing with somebody else's discard is free`() {
        val allowance = mapOf("2" to SectionAllowance(limit = 1, used = 1, remaining = 0))
        assertTrue(discardBlocked(allowance, "2", Decision.Pending, free = false))
        // Free: somebody else already refused it.
        assertFalse(discardBlocked(allowance, "2", Decision.Pending, free = true))
        // Already a discard: undoing and redoing one costs nothing new.
        assertFalse(discardBlocked(allowance, "2", Decision.Rejected, free = false))
        // No section (nobody in the photo): nothing to charge.
        assertFalse(discardBlocked(allowance, null, Decision.Pending, free = false))
    }

    @Test
    fun `a discard is free only when another member refused it`() {
        val rows = listOf(
            row("a", Decision.Rejected),
            row("b", Decision.Pending),
        )
        assertTrue(discardIsFree(rows, memberId = "b"))
        assertFalse(discardIsFree(rows, memberId = "a"))
    }

    @Test
    fun `limits round-trip through the six inputs, and a non-number refuses the lot`() {
        val limits = mapOf("1" to 2, "6+" to 0)
        val inputs = limitsToInputs(limits)
        assertEquals("2", inputs["1"])
        assertEquals("", inputs["3"])
        assertEquals("0", inputs["6+"])
        assertEquals(limits, limitsFromInputs(inputs))
        // Blank is left out, not sent as zero.
        assertEquals(emptyMap(), limitsFromInputs(limitsToInputs(emptyMap())))
        assertNull(limitsFromInputs(inputs + ("2" to "two")))
        assertNull(limitsFromInputs(inputs + ("2" to "-1")))
    }

    @Test
    fun `a head count falls in its section`() {
        assertNull(sectionOf(0))
        assertEquals("1", sectionOf(1))
        assertEquals("5", sectionOf(5))
        assertEquals("6+", sectionOf(6))
        assertEquals("6+", sectionOf(14))
    }

    // -- filters -----------------------------------------------------------------------

    @Test
    fun `each kind asks the service its own question`() {
        assertEquals(emptyMap(), PhotoFilters().toQuery())
        assertEquals(mapOf("people" to 1), PhotoFilters(kind = PhotoKind.Solo).toQuery())
        assertEquals(mapOf("group" to true), PhotoFilters(kind = PhotoKind.Group).toQuery())
        assertEquals(mapOf("people" to 3), PhotoFilters(kind = PhotoKind.Group, size = "3").toQuery())
        assertEquals(mapOf("people_min" to 6), PhotoFilters(kind = PhotoKind.Group, size = "6+").toQuery())
        assertEquals(mapOf("has" to "unknown"), PhotoFilters(kind = PhotoKind.Unknown).toQuery())
        assertEquals(mapOf("has" to "nofaces"), PhotoFilters(kind = PhotoKind.NoFaces).toQuery())
        assertEquals(mapOf("has" to "failed"), PhotoFilters(kind = PhotoKind.Failed).toQuery())
        assertEquals(mapOf("has" to "processing"), PhotoFilters(kind = PhotoKind.Processing).toQuery())
    }

    @Test
    fun `a shoot label is trimmed, and a blank one is not sent`() {
        assertEquals(emptyMap(), PhotoFilters(shoot = "   ").toQuery())
        assertEquals(mapOf("shoot" to "day-12"), PhotoFilters(shoot = "  day-12  ").toQuery())
    }

    @Test
    fun `the state, member and agent each narrow the gallery`() {
        val query = PhotoFilters(
            state = PublicState.Blocked,
            member = "m1",
            agent = "u9",
        ).toQuery()
        assertEquals("blocked", query["state"])
        assertEquals("m1", query["member"])
        assertEquals("u9", query["agent"])
    }

    @Test
    fun `a group's count is the sum of its sizes`() {
        val summary = StillsSummary(
            total = 90,
            bySection = mapOf("1" to 10, "2" to 20, "3" to 5, "6+" to 2),
            needsNames = 4,
            noPeople = 1,
        )
        assertEquals(90, countFor(summary, PhotoKind.All))
        assertEquals(10, countFor(summary, PhotoKind.Solo))
        assertEquals(27, countFor(summary, PhotoKind.Group))
        assertEquals(5, countFor(summary, PhotoKind.Group, "3"))
        assertEquals(0, countFor(summary, PhotoKind.Group, "4"))
        assertEquals(4, countFor(summary, PhotoKind.Unknown))
        assertEquals(1, countFor(summary, PhotoKind.NoFaces))
        assertNull(countFor(null, PhotoKind.All))
    }

    // -- the loaded list ---------------------------------------------------------------

    @Test
    fun `a page replaces a photo already listed where it stands`() {
        val list = listOf(tile("a", 30), tile("b", 20))
        val next = appendPage(list, listOf(tile("b", 20, rev = 2), tile("c", 10)))
        assertEquals(listOf("a", "b", "c"), next.map { it.id })
        assertEquals(2, next[1].rev)
        assertSame(list, appendPage(list, emptyList()))
    }

    @Test
    fun `an asked photo that did not come back leaves the list`() {
        val list = listOf(tile("a", 30), tile("b", 20), tile("c", 10))
        val next = patchList(list, asked = listOf("b"), found = emptyList(), hasMore = false)
        assertEquals(listOf("a", "c"), next.map { it.id })
    }

    @Test
    fun `a changed photo is replaced and nothing else moves`() {
        val list = listOf(tile("a", 30), tile("b", 20))
        val next = patchList(list, asked = listOf("b"), found = listOf(tile("b", 20, rev = 7)), hasMore = false)
        assertEquals(listOf("a", "b"), next.map { it.id })
        assertEquals(7, next[1].rev)
    }

    @Test
    fun `an unchanged answer leaves the very same list, so nothing recomposes`() {
        val list = listOf(tile("a", 30), tile("b", 20))
        assertSame(list, patchList(list, asked = listOf("a"), found = listOf(tile("a", 30)), hasMore = false))
    }

    @Test
    fun `a photo older than everything loaded waits for its page`() {
        val list = listOf(tile("a", 30), tile("b", 20))
        // Older than the oldest loaded tile, and there are more pages to come.
        val older = tile("z", 5)
        assertSame(list, patchList(list, asked = listOf("z"), found = listOf(older), hasMore = true))
        // With nothing more to load it belongs at the end.
        val next = patchList(list, asked = listOf("z"), found = listOf(older), hasMore = false)
        assertEquals(listOf("a", "b", "z"), next.map { it.id })
    }

    @Test
    fun `a newer photo lands at the top, in the server's order`() {
        val list = listOf(tile("a", 30), tile("b", 20))
        val next = patchList(list, asked = listOf("n"), found = listOf(tile("n", 40)), hasMore = true)
        assertEquals(listOf("n", "a", "b"), next.map { it.id })
    }

    @Test
    fun `two photos stamped the same hour sort by id, newest first`() {
        val list = listOf(tile("b", 20), tile("a", 20))
        val next = patchList(list, asked = listOf("c"), found = listOf(tile("c", 20)), hasMore = false)
        assertEquals(listOf("c", "b", "a"), next.map { it.id })
    }

    @Test
    fun `deleted photos leave, and an untouched list is the same list`() {
        val list = listOf(tile("a", 30), tile("b", 20))
        assertEquals(listOf("b"), removeIds(list, listOf("a")).map { it.id })
        assertSame(list, removeIds(list, listOf("nope")))
    }

    @Test
    fun `ids are asked for in batches the service accepts`() {
        val ids = (1..450).map { "p$it" }
        val batches = idBatches(ids)
        assertEquals(listOf(200, 200, 50), batches.map { it.size })
        // Repeats are asked about once.
        assertEquals(1, idBatches(listOf("a", "a", "a")).single().size)
    }

    // -- picking files -----------------------------------------------------------------

    @Test
    fun `a camera RAW is refused with its own reason, not as "not a photo"`() {
        val types = listOf("image/jpeg")
        val screened = screenPicks(
            listOf(
                pick("A001.CR3", 2_000_000, "")!!,
                pick("A002.JPG", 2_000_000, "image/jpeg")!!,
            ),
            types,
            maxBytes = 50_000_000,
        )
        assertEquals(listOf("A002.JPG"), screened.ok.map { it.name })
        assertEquals(PickRefusal.Raw, screened.refused.single().reason)
    }

    @Test
    fun `an empty file, an oversized one and a type the service refuses each say why`() {
        val screened = screenPicks(
            listOf(
                pick("a.jpg", 0, "image/jpeg")!!,
                pick("b.jpg", 99, "image/jpeg")!!,
                pick("c.tiff", 10, "image/tiff")!!,
            ),
            types = listOf("image/jpeg"),
            maxBytes = 50,
        )
        assertEquals(
            listOf(PickRefusal.Empty, PickRefusal.Size, PickRefusal.Type),
            screened.refused.map { it.reason },
        )
    }

    @Test
    fun `what a folder drags in is dropped without a word`() {
        val screened = screenPicks(
            listOf(pick(".DS_Store", 6000, "")!!, pick("Thumbs.db", 10, "")!!),
            types = listOf("image/jpeg"),
            maxBytes = 50_000_000,
        )
        assertTrue(screened.ok.isEmpty())
        assertTrue(screened.refused.isEmpty())
    }

    @Test
    fun `a type the OS got wrong is put right from the extension`() {
        assertEquals("image/jpeg", stillsTypeOf("a.jpg", "image/jpg"))
        assertEquals("image/jpeg", stillsTypeOf("a.jpg", "image/pjpeg"))
        // macOS reports nothing for HEIC.
        assertEquals("image/heic", stillsTypeOf("IMG_0001.HEIC", null))
        assertEquals("image/png", stillsTypeOf("x.png", ""))
        assertEquals("", stillsTypeOf("notes.rtf", null))
    }

    @Test
    fun `a size reads as the upload hint prints it`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
        assertEquals("2 KB", formatBytes(2048))
        assertEquals("1.5 MB", formatBytes(1_572_864))
        assertEquals("40 MB", formatBytes(41_943_040))
        assertEquals("1.0 GB", formatBytes(1_073_741_824))
    }

    // -- links -------------------------------------------------------------------------

    @Test
    fun `a link is kept while it works, so a patched tile does not reload its image`() {
        var now = 0L
        val cache = StillsUrlCache { now }
        val first = cache.stable("p:thumb", "https://one", expiresAt = 3_600_000)
        assertEquals("https://one", first)
        // Same file, a freshly signed link, still well inside the hour.
        now = 1_000_000
        assertEquals("https://one", cache.stable("p:thumb", "https://two", expiresAt = 3_600_000))
        // Shortly before it stops working, the new one is taken.
        now = 3_600_000 - 1_000
        assertEquals("https://three", cache.stable("p:thumb", "https://three", expiresAt = 7_200_000))
    }

    private fun row(memberId: String, state: Decision) =
        com.zillit.desktop.feature.selectstills.domain.ApprovalRow(memberId = memberId, state = state)

    private fun tile(id: String, sortAt: Long, rev: Long = 1) =
        PhotoTile(id = id, sortAt = sortAt, rev = rev, status = PhotoStatus.Done)

    private fun pick(name: String, size: Long, type: String): StillsPick? =
        StillsPick(path = "/card/$name", name = name, size = size, type = type)
}
