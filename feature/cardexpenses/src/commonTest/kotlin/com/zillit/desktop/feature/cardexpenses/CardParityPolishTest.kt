package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.cardexpenses.data.TransactionDto
import com.zillit.desktop.feature.cardexpenses.domain.CardUtilisation
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.DealRate
import com.zillit.desktop.feature.cardexpenses.domain.otherStatusLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The small web-parity rules: the deal behind a salary cap, the status words, the utilisation figure. */
class CardParityPolishTest {

    // -- the requester's deal -------------------------------------------------------

    /** `requestCap.js`: `rates.weekly.rate` in `pay_currency || contract_currency`. */
    @Test
    fun `the weekly rate is read off the deal's rates, pay currency first`() {
        val deal = Json.parseToJsonElement(
            """{"rates":{"weekly":{"rate":"850.50"},"pay_currency":"EUR","contract_currency":"GBP"}}""",
        ).jsonObject
        assertEquals(DealRate(850.5, "EUR"), DealRate.of(deal))

        val contractOnly = Json.parseToJsonElement(
            """{"rates":{"weekly":{"rate":600},"contract_currency":{"code":"USD","symbol":"$"}}}""",
        ).jsonObject
        assertEquals(DealRate(600.0, "USD"), DealRate.of(contractOnly))
    }

    /** A flat-fee deal carries a 0 rate, which the guard falls back from rather than capping at 0. */
    @Test
    fun `a deal with no weekly rate reads as 0, and no deal reads as none`() {
        assertEquals(DealRate(0.0, null), DealRate.of(Json.parseToJsonElement("""{"rates":{}}""").jsonObject))
        assertEquals(DealRate(0.0, null), DealRate.of(Json.parseToJsonElement("{}").jsonObject))
        assertNull(DealRate.of(null))
    }

    // -- All Transactions' status words --------------------------------------------------

    /** `TransactionTable.jsx:9-22, 283`: coded / matched / declined have words, anything else prints as sent. */
    @Test
    fun `a status outside the workflow list reads as the web words it, never Unknown`() {
        assertEquals("Declined", transaction("declined").otherStatusLabel)
        assertEquals("Coded", transaction("coded").otherStatusLabel)
        assertEquals("Matched", transaction("matched").otherStatusLabel)
        assertEquals("settled_late", transaction("settled_late").otherStatusLabel)
    }

    @Test
    fun `a workflow status keeps its own pill, and a blank one stays unlabelled`() {
        assertNull(transaction("pending_code").otherStatusLabel)
        assertEquals(CardWorkflowStatus.PendingCode, transaction("pending_code").status)
        assertNull(transaction(null).otherStatusLabel)
        assertNull(transaction("  ").otherStatusLabel)
    }

    // -- utilisation -----------------------------------------------------------------------

    /** `CardDetailModal.jsx:278`: `Math.round`, so 99.6% reads 100%. */
    @Test
    fun `utilisation rounds to the nearest percent, halves up`() {
        assertEquals(100, CardUtilisation.percent(spent = 99.6, limit = 100.0))
        assertEquals(99, CardUtilisation.percent(spent = 99.4, limit = 100.0))
        assertEquals(13, CardUtilisation.percent(spent = 12.5, limit = 100.0), "JavaScript rounds .5 up")
        assertEquals(0, CardUtilisation.percent(spent = 40.0, limit = 0.0))
    }

    private fun transaction(status: String?) = assertNotNull(
        HttpClientFactory.json.decodeFromString(
            TransactionDto.serializer(),
            buildString {
                append("""{"id":"t1","amount":"4.50"""")
                if (status != null) append(""","status":"$status"""")
                append("}")
            },
        ).toDomain(),
    )
}
