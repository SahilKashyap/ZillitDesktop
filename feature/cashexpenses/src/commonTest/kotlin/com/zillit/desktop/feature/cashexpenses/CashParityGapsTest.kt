package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.data.BatchDto
import com.zillit.desktop.feature.cashexpenses.data.cashLenient
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.ReasonedAction
import com.zillit.desktop.feature.cashexpenses.ui.canConfirm
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Four small gaps against the web: the BACS payee on Payment Routing, the
 * reason a rejection waits for, and the float status words.
 */
class CashParityGapsTest {

    private fun batch(json: String) =
        cashLenient.decodeFromJsonElement(BatchDto.serializer(), Json.parseToJsonElement(json)).toDomain()!!

    // -- Payment Routing's payee ---------------------------------------------------------

    @Test
    fun `a routed batch carries its payee's account from bank_details`() {
        val payee = batch(
            """{"id":"b1","bank_details":
                {"account_name":"Sam Grip","sort_code":"12-34-56","account_number":"12345678"}}""",
        ).payee

        assertEquals("Sam Grip", payee?.accountName)
        assertEquals("12-34-56 · 12345678", payee?.sortAndAccount)
    }

    @Test
    fun `the payee falls back to settlement_details, whichever shape it arrives in`() {
        val inObject = batch(
            """{"id":"b1","settlement_details":{"payment_method":"BACS","bank_details":{"account_name":"Ann"}}}""",
        )
        val inString = batch(
            """{"id":"b1","settlement_details":"{\"bank_details\":{\"sort_code\":\"11-22-33\"}}"}""",
        )

        assertEquals("Ann", inObject.payee?.accountName)
        assertEquals("", inObject.payee?.sortAndAccount)
        assertEquals("11-22-33", inString.payee?.sortCode)
    }

    @Test
    fun `a batch with no account on it has no payee`() {
        assertNull(batch("""{"id":"b1"}""").payee)
        assertNull(batch("""{"id":"b1","bank_details":{"account_name":" ","sort_code":null}}""").payee)
        // A payroll route has no bank_details at all.
        assertNull(batch("""{"id":"b1","settlement_details":{"payment_method":"PAYROLL"}}""").payee)
    }

    // -- the reason a rejection waits for ----------------------------------------------------

    @Test
    fun `reject and escalate confirm only once a reason is written`() {
        for (action in ReasonedAction.entries) {
            val blank = CashPrompt.WithReason(action, "id", "Title", "Label")
            assertFalse(blank.canConfirm(null), "$action with no reason")
            assertFalse(blank.copy(reason = "   ").canConfirm(null), "$action with only spaces")
            assertTrue(blank.copy(reason = "Duplicate receipt").canConfirm(null), "$action with a reason")
        }
    }

    // -- float status words ---------------------------------------------------------------------

    @Test
    fun `the float statuses read as the web names them`() {
        assertEquals("Awaiting Approval", FloatStatus.AwaitingApproval.label)
        assertEquals("Active", FloatStatus.Active.label)
        assertEquals("Collected", FloatStatus.Collected.label)
        // The legacy alias is still cash in hand, whatever it is called.
        assertTrue(FloatStatus.Active.isSubmittable)
    }
}
