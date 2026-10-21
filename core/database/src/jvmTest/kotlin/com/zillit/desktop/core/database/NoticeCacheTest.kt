package com.zillit.desktop.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The notice-board cache: merge rather than replace, newest-window reads,
 * bounded growth, and isolation by production *and* board.
 *
 * The board scoping is the one the table this replaces got wrong — it keyed
 * rows by notice id alone, so the seven tools sharing the feed engine would
 * have written over each other.
 */
class NoticeCacheTest {

    private var clock = 1_000L

    private fun cache(): NoticeCache = caches().first

    /** The cache and the visit record behind it — the prune reads both. */
    private fun caches(): Pair<NoticeCache, ProjectVisits> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ZillitDatabase.Schema.create(driver)
        val database = ZillitDatabase(driver)
        return NoticeCache(database) { clock } to ProjectVisits(database)
    }

    private fun home(unit: String = "u1") = BoardScope("p1", "home", unit)

    private fun board(projectId: String, unit: String = "u1") = BoardScope(projectId, "home", unit)

    private fun row(id: String, at: Long) = NoticeRow(id, """{"_id":"$id","message":"cipher-$id"}""", at)

    private fun NoticeCache.ids(scope: BoardScope, limit: Int = 300): List<String> =
        newest(scope, limit).map { wire -> wire.substringAfter("\"_id\":\"").substringBefore('"') }

    @Test
    fun `a page round-trips with the server's own row intact`() {
        val cache = cache()
        cache.save(home(), listOf(row("n1", 10)))

        assertEquals(listOf("""{"_id":"n1","message":"cipher-n1"}"""), cache.newest(home()))
    }

    /** Newest first, so a limit takes the newest window and not the oldest. */
    @Test
    fun `rows come back newest first`() {
        val cache = cache()
        cache.save(home(), listOf(row("a", 100), row("c", 300), row("b", 200)))

        assertEquals(listOf("c", "b", "a"), cache.ids(home()))
    }

    /**
     * The page before and the newest page are both true at once. The old
     * table replaced the unit on every save, which would make scrolling back
     * forget where it came from the moment the board refreshed.
     */
    @Test
    fun `an older page merges in rather than replacing the newest`() {
        val cache = cache()
        cache.save(home(), listOf(row("n3", 300), row("n2", 200)))
        cache.save(home(), listOf(row("n2", 200), row("n1", 100)))

        assertEquals(listOf("n3", "n2", "n1"), cache.ids(home()), "the newest page was dropped")
        assertEquals(3, cache.count(home()), "the shared boundary post was kept twice")
    }

    @Test
    fun `a re-saved post is updated, not duplicated`() {
        val cache = cache()
        cache.save(home(), listOf(row("n1", 100)))
        cache.save(home(), listOf(NoticeRow("n1", """{"_id":"n1","message":"edited"}""", 500)))

        assertEquals(1, cache.count(home()))
        assertTrue(cache.newest(home()).single().contains("edited"))
    }

    @Test
    fun `one unit's board does not disturb another's`() {
        val cache = cache()
        cache.save(home("u1"), listOf(row("n1", 10)))
        cache.save(home("u2"), listOf(row("n2", 20)))

        assertEquals(listOf("n1"), cache.ids(home("u1")))
        assertEquals(listOf("n2"), cache.ids(home("u2")))
    }

    /** Unit ids are only unique within a board, so the board is part of the key. */
    @Test
    fun `two boards may hold the same unit id without colliding`() {
        val cache = cache()
        val onHome = BoardScope("p1", "home", "u1")
        val onInfo = BoardScope("p1", "info", "u1")
        cache.save(onHome, listOf(row("home-post", 10)))
        cache.save(onInfo, listOf(row("info-post", 20)))

        assertEquals(listOf("home-post"), cache.ids(onHome))
        assertEquals(listOf("info-post"), cache.ids(onInfo))
    }

    @Test
    fun `another production never sees this one's board`() {
        val cache = cache()
        cache.save(BoardScope("p1", "home", "u1"), listOf(row("n1", 10)))

        assertTrue(cache.newest(BoardScope("p2", "home", "u1")).isEmpty())
    }

    @Test
    fun `a deleted post is forgotten`() {
        val cache = cache()
        cache.save(home(), listOf(row("n1", 10), row("n2", 20)))

        cache.delete(home(), "n1")

        assertEquals(listOf("n2"), cache.ids(home()))
    }

    /** A delete whose payload named no unit still has to land. */
    @Test
    fun `a post can be deleted by board alone`() {
        val cache = cache()
        cache.save(home("u1"), listOf(row("n1", 10)))
        cache.save(home("u2"), listOf(row("n2", 20)))

        cache.deleteAnywhereOnBoard("p1", "home", "n1")

        assertTrue(cache.newest(home("u1")).isEmpty())
        assertEquals(listOf("n2"), cache.ids(home("u2")), "only the named post went")
    }

    @Test
    fun `clearing a production leaves the others alone`() {
        val cache = cache()
        cache.save(BoardScope("p1", "home", "u1"), listOf(row("n1", 10)))
        cache.save(BoardScope("p2", "home", "u1"), listOf(row("n2", 20)))

        cache.clearProject("p1")

        assertTrue(cache.newest(BoardScope("p1", "home", "u1")).isEmpty())
        assertEquals(listOf("n2"), cache.ids(BoardScope("p2", "home", "u1")))
    }

    /**
     * A long production's board runs to thousands of posts; this is a cache,
     * not an archive. Scrolling back must not grow the table without end.
     */
    @Test
    fun `a unit is trimmed to the newest window, keeping the newest`() {
        val cache = cache()
        cache.save(home(), (1..400).map { row("n$it", it.toLong()) })

        assertEquals(300, cache.count(home()))
        assertEquals("n400", cache.ids(home()).first(), "the newest post was trimmed away")
        assertEquals("n101", cache.ids(home()).last(), "the wrong end was trimmed")
    }

    @Test
    fun `trimming one unit does not touch another`() {
        val cache = cache()
        cache.save(home("u1"), (1..400).map { row("n$it", it.toLong()) })
        cache.save(home("u2"), listOf(row("keep", 1)))

        assertEquals(1, cache.count(home("u2")))
    }

    /**
     * A project switch deliberately keeps a board, so age is what bounds this
     * table across a year of shoots.
     */
    @Test
    fun `pruning drops boards nobody has opened in a month, and keeps the rest`() {
        val cache = cache()
        clock = 0
        cache.save(home("stale"), listOf(row("old", 10)))
        clock = 40L * 24 * 60 * 60_000
        cache.save(home("fresh"), listOf(row("new", 20)))

        cache.prune(clock)

        assertTrue(cache.newest(home("stale")).isEmpty())
        assertEquals(listOf("new"), cache.ids(home("fresh")))
    }

    /**
     * The case this retention rule exists for: somebody on a dozen
     * productions opens each one every few weeks, so every row in the one
     * they are working on today is older than the window — and pruning by row
     * age threw that board away under them, tab strip and all.
     */
    @Test
    fun `a production still being opened keeps its board however old the rows are`() {
        val (cache, visits) = caches()
        clock = 0
        cache.save(board("worked-on"), listOf(row("old", 10)))
        cache.save(board("abandoned"), listOf(row("older", 5)))
        clock = 40L * 24 * 60 * 60_000

        // Opened this morning; its posts were last read five weeks ago.
        visits.opened("worked-on", clock)

        cache.prune(clock)

        assertEquals(listOf("old"), cache.ids(board("worked-on")))
        assertTrue(cache.newest(board("abandoned")).isEmpty())
    }

    /** A reprieve expires with the open that earned it. */
    @Test
    fun `a production last opened before the window loses its board`() {
        val (cache, visits) = caches()
        clock = 0
        cache.save(board("p1"), listOf(row("old", 10)))
        visits.opened("p1", clock)
        clock = 40L * 24 * 60 * 60_000

        cache.prune(clock)

        assertTrue(cache.newest(board("p1")).isEmpty())
    }

    /**
     * No record is not a reprieve. The first launch after this rule landed
     * knows of no opens at all, and must prune exactly what the build before
     * it would have — otherwise shipping it is a disk leak on every machine.
     */
    @Test
    fun `a production with no recorded open is pruned by age, as before`() {
        val (cache, _) = caches()
        clock = 0
        cache.save(board("never-recorded"), listOf(row("old", 10)))
        clock = 40L * 24 * 60 * 60_000

        cache.prune(clock)

        assertTrue(cache.newest(board("never-recorded")).isEmpty())
    }

    @Test
    fun `saving nothing is not a way to empty a board`() {
        val cache = cache()
        cache.save(home(), listOf(row("n1", 10)))

        cache.save(home(), emptyList())

        assertEquals(listOf("n1"), cache.ids(home()))
    }
}
