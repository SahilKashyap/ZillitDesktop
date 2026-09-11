package com.zillit.desktop.feature.bankrec

import com.zillit.desktop.feature.bankrec.data.ExceptionDto
import com.zillit.desktop.feature.bankrec.data.FxVarianceDto
import com.zillit.desktop.feature.bankrec.data.PeriodDto
import com.zillit.desktop.feature.bankrec.data.PortalLinkDto
import com.zillit.desktop.feature.bankrec.data.RulesSettingsDto
import com.zillit.desktop.feature.bankrec.data.TransactionDto
import com.zillit.desktop.feature.bankrec.data.WorkspaceDto
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.ExceptionType
import com.zillit.desktop.feature.bankrec.domain.FraudDetection
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.FraudType
import com.zillit.desktop.feature.bankrec.domain.LedgerEntryKind
import com.zillit.desktop.feature.bankrec.domain.MatchRule
import com.zillit.desktop.feature.bankrec.domain.PeriodStatus
import com.zillit.desktop.feature.bankrec.domain.PortalPermission
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * The shapes this service actually speaks.
 *
 * Written from response bodies rather than from the models, because every bug
 * this file exists to catch decodes cleanly and renders wrong: a matched line
 * that reads unmatched, a euro account whose every row reads as a foreign
 * payment, a fraud check that quietly stops running.
 *
 * ## The usual endpoint probe does not work on this host
 *
 * `bankreconciliationapi` runs its moduledata guard before routing, so an
 * invented path answers 406 exactly as a real one does. These bodies come from
 * the web's own client, which is the authority.
 */
class BankRecWireTest {

    @Test
    fun `a period is read with its counts and balances`() {
        val period = json.decodeFromString(
            PeriodDto.serializer(),
            """
            {"id":"p1","period":"1743465600000","bank_account_id":"b1","status":"in_progress",
             "total_txns":"47","matched_count":39,"suggested_count":4,"unmatched_count":4,
             "fraud_count":2,"closing_bank":"12500.55","closing_zillit":12000.0,
             "difference":"500.55","created_at":1743465600000}
            """.trimIndent(),
        ).toDomain()

        assertEquals(PeriodStatus.InProgress, period.status)
        assertEquals(1_743_465_600_000L, period.periodMillis)
        assertEquals(47, period.totalTxns)
        assertEquals(12_500.55, period.closingBank)
        assertTrue(period.isOpen)
        assertEquals(82, period.matchedPercent)
    }

    /**
     * Money and epochs arrive as numbers and as text, depending on the column.
     *
     * Typing them as `Double?` drops every quoted one silently, and a balance
     * that reads zero is a difference somebody chases for an afternoon.
     */
    @Test
    fun `a quoted number reads the same as a bare one`() {
        val quoted = json.decodeFromString(
            PeriodDto.serializer(),
            """{"id":"p1","closing_bank":"1234.50","total_txns":"12"}""",
        ).toDomain()

        assertEquals(1234.5, quoted.closingBank)
        assertEquals(12, quoted.totalTxns)
    }

    /** Money out is negative, whatever the statement calls its two columns. */
    @Test
    fun `a debit is money out and a credit is money in`() {
        val paid = json.decodeFromString(
            TransactionDto.serializer(),
            """{"id":"t1","debit":250.0,"credit":0}""",
        ).toDomain()
        val received = json.decodeFromString(
            TransactionDto.serializer(),
            """{"id":"t2","debit":0,"credit":80.0}""",
        ).toDomain()

        assertEquals(-250.0, paid.amount)
        assertTrue(paid.isMoneyOut)
        assertEquals(80.0, received.amount)
        assertFalse(received.isMoneyOut)
    }

    /**
     * A foreign payment is one carrying a foreign amount.
     *
     * Judging it by "currency is not the project's" reads every line on a euro
     * account as an FX payment, which is the whole account.
     */
    @Test
    fun `FX is decided by a foreign amount, not by the currency`() {
        val onEuroAccount = json.decodeFromString(
            TransactionDto.serializer(),
            """{"id":"t1","debit":100,"currency":"EUR","status":"unmatched"}""",
        ).toDomain()
        val actuallyForeign = json.decodeFromString(
            TransactionDto.serializer(),
            """
            {"id":"t2","debit":100,"currency":"USD","foreign_amount":125.0,
             "budget_rate":1.2,"bank_rate":1.25,"fx_variance":-4.2,
             "fx_variance_id":"v1","fx_variance_status":"unposted","status":"unmatched"}
            """.trimIndent(),
        ).toDomain()

        assertNull(onEuroAccount.fx)
        assertEquals(TxnStatus.Unmatched, onEuroAccount.effectiveStatus)
        assertNotNull(actuallyForeign.fx)
        assertEquals(TxnStatus.Fx, actuallyForeign.effectiveStatus)
        assertFalse(actuallyForeign.fx.isPosted)
    }

    /**
     * A matched line reads as matched, whatever else was said about it.
     *
     * A fraud flag that has been accepted, dismissed or escalated has been
     * dealt with, and a line still carrying it should not go on reading as
     * outstanding.
     */
    @Test
    fun `matched beats every other status, and an actioned flag stops flagging`() {
        val matchedButFlagged = json.decodeFromString(
            TransactionDto.serializer(),
            """{"id":"t1","status":"matched","fraud_type":"duplicate_payment","fraud_status":"accepted"}""",
        ).toDomain()
        val stillFlagged = json.decodeFromString(
            TransactionDto.serializer(),
            """{"id":"t2","status":"unmatched","fraud_type":"split_payment","fraud_status":"active"}""",
        ).toDomain()
        val dismissed = json.decodeFromString(
            TransactionDto.serializer(),
            """{"id":"t3","status":"unmatched","fraud_type":"split_payment","fraud_status":"dismissed"}""",
        ).toDomain()

        assertEquals(TxnStatus.Matched, matchedButFlagged.effectiveStatus)
        assertFalse(matchedButFlagged.hasActiveFraud)
        assertEquals(TxnStatus.FraudFlag, stillFlagged.effectiveStatus)
        assertEquals(FraudType.SplitPayment, stillFlagged.fraudType)
        assertEquals(FraudStatus.Dismissed, dismissed.fraudStatus)
        assertEquals(TxnStatus.Unmatched, dismissed.effectiveStatus)
    }

    /**
     * Id lists arrive as arrays and as the text of arrays.
     *
     * Reading only the array shape leaves a matched line looking unmatched,
     * and the accountant reconciles it a second time.
     */
    @Test
    fun `an id list is read whether it is an array or the text of one`() {
        val asArray = json.decodeFromString(
            TransactionDto.serializer(),
            """{"id":"t1","matched_invoice_ids":["i1","i2"]}""",
        ).toDomain()
        val asText = json.decodeFromString(
            TransactionDto.serializer(),
            """{"id":"t2","matched_invoice_ids":"[\"i1\",\"i2\"]"}""",
        ).toDomain()

        assertEquals(listOf("i1", "i2"), asArray.matchedInvoiceIds)
        assertEquals(asArray.matchedInvoiceIds, asText.matchedInvoiceIds)
    }

    /**
     * The workspace's `invoices` list is not only invoices.
     *
     * It also carries quick-added transactions and posted FX variances, told
     * apart by `entity_type` — and matching sends the type with the id, so
     * reading them all as invoices matches against the wrong table.
     */
    @Test
    fun `the ledger side carries three kinds of entry`() {
        val data = json.decodeFromString(
            WorkspaceDto.serializer(),
            """
            {"transactions":[],
             "invoices":[
               {"id":"i1","entity_type":"invoice","vendor_name":"Panavision",
                "invoice_number":"INV-88","gross_amount":1200.0,"tr_ids":["t1"]},
               {"id":"q1","entity_type":"transaction","title":"Bank charge",
                "debit":35.0,"ledger_description":"[\"7900\",\"Bank charges\"]","tr_ids":[]},
               {"id":"f1","entity_type":"fx","title":"FX variance","credit":4.2,"tr_ids":[]}
             ]}
            """.trimIndent(),
        )

        val rows = data.invoices.orEmpty().map { it.toDomain() }
        assertEquals(
            listOf(LedgerEntryKind.Invoice, LedgerEntryKind.Transaction, LedgerEntryKind.FxPosting),
            rows.map { it.kind },
        )
        assertEquals(-1200.0, rows[0].amount)
        assertTrue(rows[0].isMatched)
        assertEquals(-35.0, rows[1].amount)
        assertEquals("7900 · Bank charges", rows[1].reference)
        assertEquals(4.2, rows[2].amount)
        assertFalse(rows[2].isMatched)
    }

    /** The ledger row's own id is not the record's, and matching uses the record's. */
    @Test
    fun `a ledger row keeps both its ids`() {
        val row = json.decodeFromString(
            WorkspaceDto.serializer(),
            """{"invoices":[{"id":"inv-1","ledger_id":"led-9","entity_type":"invoice"}]}""",
        ).invoices!!.first().toDomain()

        assertEquals("led-9", row.id)
        assertEquals("inv-1", row.entityId)
    }

    @Test
    fun `an exception carries its transaction and its type`() {
        val row = json.decodeFromString(
            ExceptionDto.serializer(),
            """
            {"id":"e1","period_id":"p1","exception_type":"bank_charge","status":"under_investigation",
             "title":"Monthly service charge","transaction":{"id":"t9","debit":35.0,"currency":"GBP"}}
            """.trimIndent(),
        ).toDomain()

        assertEquals(ExceptionType.BankCharge, row.type)
        assertEquals(ExceptionStatus.UnderInvestigation, row.status)
        assertTrue(row.status.isOutstanding)
        assertEquals(-35.0, row.amount)
        assertEquals("GBP", row.currency)
    }

    /** A type this client does not know is Unknown, not the first entry. */
    @Test
    fun `an unknown exception type is read as unknown`() {
        val row = json.decodeFromString(
            ExceptionDto.serializer(),
            """{"id":"e1","exception_type":"something_new"}""",
        ).toDomain()

        assertEquals(ExceptionType.Unknown, row.type)
    }

    /**
     * The FX row's `gbp` field names are not sterling.
     *
     * They are the project's default currency, whatever that is, and a client
     * that believes the field name renders a euro production's variances with
     * a pound sign.
     */
    @Test
    fun `an FX variance is read with its rates and its direction`() {
        val row = json.decodeFromString(
            FxVarianceDto.serializer(),
            """
            {"id":"v1","period_id":"p1","invoice_currency":"USD","foreign_amount":1500.0,
             "budget_gbp":1200.0,"gbp_paid":1240.0,"variance":"-40.0","budget_rate":1.25,
             "bank_rate":1.21,"status":"unposted","created_at":1743465600000,
             "transaction":{"id":"t1","vendor_name":"Kodak","reference":"INV-1"}}
            """.trimIndent(),
        ).toDomain()

        assertEquals(1200.0, row.budgetAmount)
        assertEquals(1240.0, row.paidAmount)
        assertEquals(-40.0, row.variance)
        assertFalse(row.isGain)
        assertFalse(row.isPosted)
        assertEquals("Kodak", row.vendorName)
    }

    /**
     * A fraud check is a boolean when it has no threshold and an object when
     * it does.
     *
     * Storing an object for a boolean rule writes a shape the engine does not
     * read, and the check quietly stops running.
     */
    @Test
    fun `the rules read both shapes and fall back to the defaults`() {
        val settings = json.decodeFromString(
            RulesSettingsDto.serializer(),
            """
            {"auto_match_rules":{"amt_ref_match":true,"amt_fuzzy_vendor_match":false},
             "fraud_detections":{"bank_change_detection":false,
               "split_pay_threshold":{"enabled":true,"amount":7500}}}
            """.trimIndent(),
        ).toDomain()

        assertEquals(true, settings.autoMatch[MatchRule.AmountAndReference.key])
        assertEquals(false, settings.autoMatch[MatchRule.AmountAndVendor.key])
        assertEquals(false, settings.fraud[FraudDetection.BankChange.key]?.enabled)
        assertNull(settings.fraud[FraudDetection.BankChange.key]?.amount)
        assertEquals(7500.0, settings.fraud[FraudDetection.SplitPayment.key]?.amount)
        // Untouched checks keep the defaults rather than reading as switched off.
        assertEquals(true, settings.fraud[FraudDetection.DuplicateDetection.key]?.enabled)
        assertEquals(10_000.0, settings.fraud[FraudDetection.RoundLargePayments.key]?.amount)
    }

    @Test
    fun `an empty rules answer is every check on, not every check off`() {
        val settings = json.decodeFromString(RulesSettingsDto.serializer(), "{}").toDomain()

        assertTrue(settings.fraud.values.all { it.enabled })
        assertTrue(settings.autoMatch.values.all { it })
    }

    /** Permissions arrive as an array or as the text of one, like every id list. */
    @Test
    fun `a portal link's permissions are read in both shapes`() {
        val rows = json.decodeFromString(
            ListSerializer(PortalLinkDto.serializer()),
            """
            [{"id":"l1","token":"abc","recipient_name":"James Whitford",
              "recipient_email":"j@example.com","org_type":"completion_guarantor",
              "period_id":"p1","permissions":["balances","exceptions"],"status":"active",
              "view_count":3},
             {"id":"l2","permissions":"[\"fx_variance\"]","status":"revoked"}]
            """.trimIndent(),
        ).map { it.toDomain() }

        assertEquals(
            setOf(PortalPermission.Balances, PortalPermission.Exceptions),
            rows[0].permissions,
        )
        assertTrue(rows[0].isActive)
        assertEquals(3, rows[0].viewCount)
        assertEquals(setOf(PortalPermission.FxVariance), rows[1].permissions)
        assertFalse(rows[1].isActive)
    }

    /** A permission this client does not know is dropped rather than guessed at. */
    @Test
    fun `an unknown permission is dropped, not folded onto another`() {
        val link = json.decodeFromString(
            PortalLinkDto.serializer(),
            """{"id":"l1","permissions":["balances","something_new"]}""",
        ).toDomain()

        assertEquals(setOf(PortalPermission.Balances), link.permissions)
    }
}
