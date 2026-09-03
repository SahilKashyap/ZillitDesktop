package com.zillit.desktop.core.badges

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BadgeStoreTest {

    private fun counts(vararg pairs: Pair<String, Int>) = BadgeCounts(byTool = pairs.toMap())


    @Test
    fun `a successful refresh replaces the counts`() = runTest {
        var next = counts("email_tool" to 3)
        val store = BadgeStore { ZillitResult.Success(next) }

        store.refresh()
        assertEquals(3, store.counts.value["email_tool"])

        next = counts("email_tool" to 7)
        store.refresh()
        assertEquals(7, store.counts.value["email_tool"], "counts are replaced, not accumulated")
    }

    @Test
    fun `a failed refresh leaves the previous counts standing`() = runTest {
        // A dropped request is not evidence that everything has been read.
        // Blanking every badge on a flaky connection would state something false.
        var fail = false
        val store = BadgeStore {
            if (fail) ZillitResult.Failure(ZillitError.NoConnection())
            else ZillitResult.Success(counts("email_tool" to 4))
        }

        store.refresh()
        fail = true
        store.refresh()

        assertEquals(4, store.counts.value["email_tool"], "a failed refresh cleared the badges")
    }

    @Test
    fun `clear empties everything`() = runTest {
        // Counts belong to a production; they must not survive a switch.
        val store = BadgeStore { ZillitResult.Success(counts("email_tool" to 4)) }
        store.refresh()

        store.clear()

        assertTrue(store.counts.value.isEmpty)
    }

    private fun full() = BadgeCounts(
        bySection = mapOf(BadgeSections.TOOLS to 5, BadgeSections.HOME to 3, BadgeSections.CNC to 2),
        byTool = mapOf("email_tool" to 4, "sides_tool" to 1),
        byUnit = mapOf("unit-a" to 3),
    )

    /**
     * `notification:silent` names tools this person may no longer see. iOS
     * drops them from its local ledger and never shows them again; the server
     * never removes the rows, so they must stay dropped across every refresh.
     */
    @Test
    fun `a lost tool leaves the rail and its section share, and stays gone`() = runTest {
        val store = BadgeStore { ZillitResult.Success(full()) }
        store.refresh()

        store.suppress(tools = setOf("email_tool"))

        assertEquals(0, store.counts.value["email_tool"])
        assertEquals(1, store.counts.value.section(BadgeSections.TOOLS), "its share came off the section")
        assertEquals(1, store.counts.value["sides_tool"], "the other tool is untouched")

        store.refresh()
        assertEquals(0, store.counts.value["email_tool"], "the next refresh handed it back")
        assertEquals(1, store.counts.value.section(BadgeSections.TOOLS))
    }

    @Test
    fun `a lost unit comes off the home section`() = runTest {
        val store = BadgeStore { ZillitResult.Success(full()) }
        store.refresh()

        store.suppress(units = setOf("unit-a"))

        assertEquals(0, store.counts.value.unit("unit-a"))
        assertEquals(0, store.counts.value.section(BadgeSections.HOME))
    }

    /** The badge goes out when the read is sent, not a settle-delay later. */
    @Test
    fun `clearing a tool is immediate and takes its section share`() = runTest {
        val store = BadgeStore { ZillitResult.Success(full()) }
        store.refresh()

        store.clearTool("email_tool")

        assertEquals(0, store.counts.value["email_tool"])
        assertEquals(1, store.counts.value.section(BadgeSections.TOOLS))
    }

    @Test
    fun `a save frame lifts the count before any refresh`() = runTest {
        val store = BadgeStore { ZillitResult.Success(full()) }
        store.refresh()

        store.bump(section = BadgeSections.CNC, tool = null, unit = null)

        assertEquals(3, store.counts.value.section(BadgeSections.CNC))
    }

    /** A refresh that changed nothing must not redraw everything that watches. */
    @Test
    fun `an identical refresh keeps the same value`() = runTest {
        val store = BadgeStore { ZillitResult.Success(full()) }
        store.refresh()
        val before = store.counts.value

        store.refresh()

        assertSame(before, store.counts.value, "an equal refresh was emitted as new")
    }

    @Test
    fun `clear forgets what was lost`() = runTest {
        val store = BadgeStore { ZillitResult.Success(full()) }
        store.suppress(tools = setOf("email_tool"))
        store.clear()

        store.refresh()

        assertEquals(4, store.counts.value["email_tool"], "the next production inherited a suppression")
    }

}
