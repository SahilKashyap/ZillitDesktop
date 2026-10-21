package com.zillit.desktop.core.common

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.LogLevel
import io.github.aakira.napier.Napier
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One readable block per open, and nothing outside it.
 *
 * The bound is the whole design: a board is re-read on every socket arrival for
 * the rest of a session, and a trace that marked those too would bury the one
 * block somebody opened the log to read.
 */
class OpenTraceTest {

    private val lines = mutableListOf<String>()

    private val capture = object : Antilog() {
        override fun performLog(priority: LogLevel, tag: String?, throwable: Throwable?, message: String?) {
            if (tag == TRACE_TAG) lines += message.orEmpty()
        }
    }

    @BeforeTest
    fun setUp() {
        Napier.base(capture)
    }

    @AfterTest
    fun tearDown() {
        Napier.takeLogarithm()
        OpenTrace.settle("test over")
    }

    @Test
    fun `a trace runs from the production being selected to the board drawing`() {
        OpenTrace.begin(PROJECT)
        OpenTrace.mark("tabs drawn from disk (7)")
        OpenTrace.settle("25 posts from disk")

        assertEquals(3, lines.size, lines.toString())
        assertTrue(lines.first().endsWith("production selected"), lines.first())
        assertTrue(lines.last().endsWith("board on screen"), lines.last())
        // Every line says which open it belongs to, and how far into it it is.
        lines.forEach { line -> assertTrue(OFFSET.containsMatchIn(line), line) }
    }

    @Test
    fun `a production is named by its tail, never in full`() {
        OpenTrace.begin(PROJECT)

        assertTrue(lines.single().startsWith("…ee1234 "), lines.single())
        assertTrue(lines.none { it.contains(PROJECT) }, lines.toString())
    }

    @Test
    fun `nothing is marked before an open begins`() {
        OpenTrace.mark("a socket arrival, hours later")
        OpenTrace.settle("and the board redrawn")

        assertEquals(emptyList(), lines)
    }

    @Test
    fun `nothing is marked after the board is on screen`() {
        OpenTrace.begin(PROJECT)
        OpenTrace.settle("25 posts from disk")
        lines.clear()

        OpenTrace.mark("a refresh of the same board")
        OpenTrace.settle("and its answer")

        assertEquals(emptyList(), lines)
    }

    @Test
    fun `a second open closes the one left standing`() {
        OpenTrace.begin(PROJECT)
        OpenTrace.begin("another-production")
        lines.clear()

        OpenTrace.mark("tabs drawn from disk (3)")

        assertTrue(lines.single().startsWith("…uction "), lines.single())
    }

    @Test
    fun `time reports what the work cost and hands back its answer`() {
        OpenTrace.begin(PROJECT)
        lines.clear()

        val answer = OpenTrace.time("tab strip from disk") { 7 }

        assertEquals(7, answer)
        assertTrue(COST.containsMatchIn(lines.single()), lines.single())
    }

    /** An id short enough to be its own tail is left alone. */
    @Test
    fun `a short production id is not elided`() {
        OpenTrace.begin("abc")

        assertTrue(lines.single().startsWith("abc +"), lines.single())
    }

    private companion object {
        const val TRACE_TAG = "Open"
        const val PROJECT = "65f0c0ffee1234"
        val OFFSET = Regex("""^…ee1234 \+\d+ms \S""")
        val COST = Regex("""tab strip from disk took \d+ms$""")
    }
}
