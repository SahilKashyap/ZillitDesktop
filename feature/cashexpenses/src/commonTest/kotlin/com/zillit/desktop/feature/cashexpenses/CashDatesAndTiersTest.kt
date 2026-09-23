package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.data.lockDate
import com.zillit.desktop.feature.cashexpenses.domain.ApprovalTier
import com.zillit.desktop.feature.cashexpenses.domain.ApprovalTierConfig
import com.zillit.desktop.feature.cashexpenses.domain.ApprovalTiers
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.Denomination
import com.zillit.desktop.feature.cashexpenses.domain.ReconDraft
import com.zillit.desktop.feature.cashexpenses.domain.ReconItem
import com.zillit.desktop.feature.cashexpenses.domain.TierApproval
import com.zillit.desktop.feature.cashexpenses.domain.TierRule
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web's `useCrLock` bounds, its approval-chain arithmetic, and the cash count. */
class CashDatesAndTiersTest {

    @Test
    fun `a ledger date runs from the day after the lock to today`() {
        assertEquals("2026-09-11", CashDates.minimum("2026-09-10"))
        assertEquals("2026-09-23", CashDates.defaultEffective(null, today = "2026-09-23"))
        // Locked through a day after today: the first day that may be picked.
        assertEquals("2026-09-25", CashDates.defaultEffective("2026-09-24", today = "2026-09-23"))
        assertTrue(CashDates.isPostable("2026-09-20", "2026-09-10", today = "2026-09-23"))
        assertFalse(CashDates.isPostable("2026-09-10", "2026-09-10", today = "2026-09-23"), "the lock is inclusive")
        assertFalse(CashDates.isPostable("2026-09-24", null, today = "2026-09-23"), "not in the future")
        assertFalse(CashDates.isPostable("24/09/2026", null, today = "2026-09-23"))
    }

    @Test
    fun `a batch dated on or before the lock is frozen, an undated one never`() {
        val tenth = CashDates.utcMillis("2026-09-10")
        assertTrue(CashDates.isLocked(tenth, "2026-09-10"))
        assertFalse(CashDates.isLocked(tenth, "2026-09-09"))
        assertFalse(CashDates.isLocked(null, "2026-09-10"))
        assertFalse(CashDates.isLocked(tenth, null))
    }

    /** `normalizeLockedYmd`: a date, an ISO datetime, or epoch millis sliced in UTC. */
    @Test
    fun `the lock is read in every shape the services use`() {
        assertEquals("2026-05-14", lockDate(JsonPrimitive("2026-05-14")))
        assertEquals("2026-05-14", lockDate(JsonPrimitive("2026-05-14T00:00:00.000Z")))
        assertEquals("2026-05-14", lockDate(JsonPrimitive(CashDates.utcMillis("2026-05-14"))))
        assertNull(lockDate(JsonPrimitive("")))
        assertNull(lockDate(null))
    }

    @Test
    fun `the department's chain wins and an amount rule picks its approvers`() {
        val configs = listOf(
            ApprovalTierConfig("all", null, listOf(ApprovalTier(listOf(TierRule("default", null, listOf("global")))))),
            ApprovalTierConfig(
                "department",
                "art",
                listOf(
                    ApprovalTier(
                        listOf(
                            TierRule("default", null, listOf("head")),
                            TierRule("amount", 1_000.0, listOf("producer")),
                        ),
                    ),
                    // Nobody qualifies: the level is skipped and the rest renumber.
                    ApprovalTier(emptyList()),
                    ApprovalTier(listOf(TierRule("default", null, listOf("fc")))),
                ),
            ),
        )

        val small = ApprovalTiers.resolve(configs, "art", 200.0)
        assertEquals(mapOf(1 to listOf("head"), 2 to listOf("fc")), small)
        assertEquals(listOf("producer"), ApprovalTiers.resolve(configs, "art", 1_500.0)?.get(1))
        assertEquals(listOf("global"), ApprovalTiers.resolve(configs, "camera", 200.0)?.get(1))

        assertEquals(1, ApprovalTiers.next(emptyList(), small))
        assertEquals(2, ApprovalTiers.next(listOf(TierApproval("head", 1)), small))
        assertNull(ApprovalTiers.next(listOf(TierApproval("head", 1), TierApproval("fc", 2)), small))
        assertNull(ApprovalTiers.stepFor(configs, "art", 200.0, emptyList(), "fc"), "level 1 is not theirs")
    }

    /** Physical count minus the book adjusted by the reconciling items (`PCCashReconPage.jsx:372-399`). */
    @Test
    fun `the variance is what the count and the items leave`() {
        val draft = ReconDraft(
            id = "r1",
            currency = "GBP",
            openingBalance = "200",
            year = 2026,
            month = 9,
            denominations = listOf(
                Denomination("n_50", Denomination.NOTE, "50", "3"),
                Denomination("c_0.20", Denomination.COIN, "0.20", "5"),
            ),
            items = listOf(
                ReconItem(type = ReconItem.OUT, amount = "40"),
                ReconItem(type = ReconItem.IN, amount = "5"),
            ),
            computedBook = 190.0,
        )

        assertEquals(151.0, draft.physicalTotal)
        assertEquals(155.0, draft.adjustedBook)
        assertEquals(-4.0, draft.variance)
        assertEquals(200.0, draft.copy(computedBook = null).bookBalance, "the opening balance stands in")
    }
}
