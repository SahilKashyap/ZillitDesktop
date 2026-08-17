package com.zillit.desktop.core.badges

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
