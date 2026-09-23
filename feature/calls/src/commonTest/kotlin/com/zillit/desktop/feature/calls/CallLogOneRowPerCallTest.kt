package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.domain.CallLogDirection
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType
import com.zillit.desktop.feature.calls.ui.oneRowPerCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The Calls tab's list is keyed by call id, so a call the server lists twice
 * stopped the app on "Key … was already used" (2026-09-23): the first page
 * was taken verbatim and only later pages were merged by id.
 */
class CallLogOneRowPerCallTest {

    private fun row(id: String, at: Long, missed: Boolean = false) = CallLogEntry(
        callUuid = id,
        direction = CallLogDirection.Incoming,
        mode = CallMode.Private,
        type = CallType.Audio,
        missed = missed,
        durationMillis = if (missed) 0 else 5_000,
        startedAtMillis = at,
    )

    @Test
    fun `a call listed twice is one row, newest first`() {
        val folded = listOf(row("a", 100), row("b", 300), row("a", 100), row("c", 200)).oneRowPerCall()
        assertEquals(listOf("b", "c", "a"), folded.map { it.callUuid })
    }

    @Test
    fun `when the copies disagree, the answered one stands`() {
        val folded = listOf(row("a", 100, missed = true), row("a", 90)).oneRowPerCall()
        assertEquals(1, folded.size)
        assertFalse(folded.single().missed)
    }
}
