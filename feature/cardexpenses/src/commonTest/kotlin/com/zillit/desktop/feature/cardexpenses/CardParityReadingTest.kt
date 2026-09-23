package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.cardexpenses.data.AlertDto
import com.zillit.desktop.feature.cardexpenses.data.AnalyticsDto
import com.zillit.desktop.feature.cardexpenses.data.CardMetadataDto
import com.zillit.desktop.feature.cardexpenses.data.CardSettingsDto
import com.zillit.desktop.feature.cardexpenses.data.CardTopUpDto
import com.zillit.desktop.feature.cardexpenses.data.ReceiptDto
import com.zillit.desktop.feature.cardexpenses.domain.CardAlert
import com.zillit.desktop.feature.cardexpenses.domain.RequestCap
import com.zillit.desktop.feature.cardexpenses.domain.RequestCapBasis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shapes the web-parity work reads, through the application's own JSON
 * configuration — a field typed too strictly fails the **whole** response, so
 * each is decoded here from the shape the service really sends.
 */
class CardParityReadingTest {

    private val json = HttpClientFactory.json

    /**
     * `request_cap` is an object on the wire. Typed as a string, it failed the
     * whole `/settings` decode — team, coordinators and providers with it.
     */
    @Test
    fun `settings with a request cap object decode whole`() {
        val settings = json.decodeFromString(
            CardSettingsDto.serializer(),
            """
            {"team_members":[{"user_id":"u1","posting_limit":null}],
             "card_providers":[{"id":"prov_1","name":"Barclaycard","bank_id":"bank-1","company_id":"co-1",
               "custodian_account":"2100","float_min":"2150","float_max":null}],
             "request_cap":{"enabled":true,"basis":"weekly_salary","max_amount":750,"salary_multiplier":1.5}}
            """.trimIndent(),
        ).toDomain()

        assertEquals(RequestCap(true, RequestCapBasis.WeeklySalary, 750.0, 1.5), settings.requestCap)
        assertEquals("u1", settings.teamMembers.single().userId)
        val provider = settings.providers.single()
        assertEquals("bank-1", provider.bankId)
        assertEquals("co-1", provider.companyId)
        assertEquals("2100", provider.custodianAccount)
        assertEquals("2150", provider.floatMin)
        assertEquals("", provider.floatMax)
    }

    /** An older row's bare figure reads as an enabled flat cap; nothing at all as no cap. */
    @Test
    fun `a legacy or missing request cap reads sensibly`() {
        val legacy = json.decodeFromString(CardSettingsDto.serializer(), """{"request_cap":"5000"}""").toDomain()
        val missing = json.decodeFromString(CardSettingsDto.serializer(), "{}").toDomain()
        val stringified = json.decodeFromString(
            CardSettingsDto.serializer(),
            """{"request_cap":"{\"enabled\":false,\"max_amount\":10}"}""",
        ).toDomain()

        assertEquals(RequestCap(enabled = true, maxAmount = 5_000.0), legacy.requestCap)
        assertEquals(RequestCap(), missing.requestCap)
        assertEquals(RequestCap(enabled = false, maxAmount = 10.0), stringified.requestCap)
    }

    /**
     * The detail read splits coded lines from the server's own, and backs the
     * editor's net out of the stored gross at the line's rate.
     */
    @Test
    fun `a receipt detail reads its lines, flags and card figures`() {
        val receipt = json.decodeFromString(
            ReceiptDto.serializer(),
            """
            {"id":"r1","amount":"120","status":"approved","assigned_to":"u9","request_top_up":1,
             "card_limit":"500","card_balance":"380","effective_date":1754006400000,
             "processing_flags":"[{\"flag\":\"review\",\"title\":\"Big spend\"},\"query\"]",
             "line_items":[
               {"id":"l1","description":"Batteries","amount":"120","tax_rate":"20","account":"4100","quantity":1,
                "tracking_codes":{"ep":"101"}},
               {"description":"Deduction","amount":-5,"tax_amount":0,"meta":"{\"auto\":true}"},
               {"is_tax":true,"amount":0,"account":"2200"}
             ]}
            """.trimIndent(),
        ).toDomain() ?: error("no receipt")

        val processing = receipt.processing
        assertTrue(processing.loaded)
        assertEquals("u9", receipt.assignedTo)
        assertTrue(processing.requestTopUp)
        assertEquals(500.0, processing.cardLimit)
        assertEquals(380.0, processing.cardBalance)
        assertEquals(1_754_006_400_000, processing.effectiveDate)
        assertEquals(setOf("review", "query"), processing.flags)
        val line = processing.lines.single()
        assertEquals(100.0, line.net, "net = gross / (1 + rate)")
        assertEquals(20.0, line.taxRate)
        assertEquals(120.0, line.gross)
        assertEquals(2, processing.fixedLines.size)
        assertTrue(processing.fixedLines.first().countsInTotal, "an auto-deduction counts")
        assertFalse(processing.fixedLines.last().countsInTotal, "the tax line does not")
    }

    /** A queue row carries no lines, and must not pass for a loaded detail. */
    @Test
    fun `a queue row is not a loaded detail`() {
        val row = json.decodeFromString(ReceiptDto.serializer(), """{"id":"r1","amount":"12"}""").toDomain()

        assertEquals(false, row?.processing?.loaded)
    }

    /** An alert with no status is live — the web defaults it to `active`. */
    @Test
    fun `an alert with no status is active`() {
        val alert = json.decodeFromString(AlertDto.serializer(), """{"id":"a1","title":"Velocity"}""").toDomain()
        val investigating = json.decodeFromString(
            AlertDto.serializer(),
            """{"id":"a2","status":"investigating","resolution":null}""",
        ).toDomain()

        assertEquals(CardAlert.ACTIVE, alert?.status)
        assertTrue(alert?.isOpen == true)
        assertTrue(investigating?.isInvestigating == true)
    }

    @Test
    fun `metadata reads the chains and the production's override switches`() {
        val metadata = json.decodeFromString(
            CardMetadataDto.serializer(),
            """
            {"is_senior":false,"can_override":true,"card_override":"true","receipt_override":0,
             "approval_tier_configs":[
               {"scope":"all","tiers":"[{\"order\":1,\"rules\":[{\"type\":\"default\",\"user_ids\":[\"u1\"]}]}]"},
               {"scope":"department","department_id":"d1","tiers":[
                 {"order":1,"rules":[{"type":"amount","amount_threshold":"1000","user_ids":["u2"]}]}]}
             ]}
            """.trimIndent(),
        ).toDomain()

        assertTrue(metadata.cardOverride)
        assertFalse(metadata.receiptOverride)
        assertEquals(2, metadata.tierConfigs.size)
        assertEquals(listOf("u1"), metadata.tierConfigs.first().tiers.single().rules.single().userIds)
        assertEquals(1_000.0, metadata.tierConfigs.last().tiers.single().rules.single().amountThreshold)
    }

    /** `/analytics/overview` answers `{summary, by_department, by_holder}`. */
    @Test
    fun `analytics reads the summary shape`() {
        val analytics = json.decodeFromString(
            AnalyticsDto.serializer(),
            """
            {"summary":{"total_spend":1250.5,"transaction_count":12,"avg_transaction":"104.21","active_cards":3},
             "by_department":[{"department_id":"d1","total":900}],
             "by_holder":[{"user_id":"u1","total":"350.5","count":4,"card_last_four":"4821"}]}
            """.trimIndent(),
        ).toDomain()

        assertEquals(1_250.5, analytics.totalSpend)
        assertEquals(12, analytics.transactionCount)
        assertEquals(104.21, analytics.averageTransaction)
        assertEquals(3, analytics.activeCards)
        assertEquals("d1", analytics.byDepartment.single().departmentId)
        assertEquals(900.0, analytics.byDepartment.single().amount)
        assertEquals("u1", analytics.byHolder.single().userId)
        assertEquals(4, analytics.byHolder.single().count)
    }

    @Test
    fun `a top-up row carries its card's limit and balance`() {
        val topUp = json.decodeFromString(
            CardTopUpDto.serializer(),
            """{"id":"t1","amount":"300","status":"pending","card_limit":"1000","card_balance":"800"}""",
        ).toDomain() ?: error("no top-up")

        assertTrue(topUp.overfills(300.0), "800 + 300 passes the 1,000 limit")
        assertFalse(topUp.overfills(200.0))
    }
}
