package com.zillit.desktop.feature.bankrec

import com.zillit.desktop.feature.bankrec.domain.FraudAuditEntry
import com.zillit.desktop.feature.bankrec.ui.AuditLogState
import com.zillit.desktop.feature.bankrec.ui.AuditSort
import com.zillit.desktop.feature.bankrec.ui.visibleEntries
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Audit Log's Action column orders by the raw action, as the web does, not by its label. */
class AuditSortTest {

    private val entries = listOf(
        FraudAuditEntry(id = "1", action = "escalated"),
        FraudAuditEntry(id = "2", action = "created"),
        FraudAuditEntry(id = "3", action = "accepted"),
    )

    @Test
    fun `the action column sorts by the raw action`() {
        // By label "Escalated to Finance" would lead "Fraud detected"; by wire value created leads escalated.
        val state = AuditLogState(entries = entries, sort = AuditSort.Action, ascending = true)
        assertEquals(listOf("3", "2", "1"), state.visibleEntries().map { it.id })
        assertEquals(listOf("1", "2", "3"), state.copy(ascending = false).visibleEntries().map { it.id })
    }
}
