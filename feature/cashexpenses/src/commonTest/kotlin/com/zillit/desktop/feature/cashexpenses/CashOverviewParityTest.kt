package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.data.DepartmentOverviewDto
import com.zillit.desktop.feature.cashexpenses.data.MyOverviewDto
import com.zillit.desktop.feature.cashexpenses.data.cashLenient
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashStats
import com.zillit.desktop.feature.cashexpenses.domain.CashSummary
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.ui.CashBadges
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.pages.actionItems
import com.zillit.desktop.feature.cashexpenses.ui.pages.daysSince
import com.zillit.desktop.feature.cashexpenses.ui.parentBadgeKeys
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
/** The overview pages' wire keys, the parent chips' key lists, and the Settings gate — as the web has them. */
class CashOverviewParityTest {

    private fun <T> decode(serializer: KSerializer<T>, json: String): T =
        cashLenient.decodeFromJsonElement(serializer, Json.parseToJsonElement(json))

    // -- My Overview: receipt rows, not batches ---------------------------------------

    @Test
    fun `my overview claims are receipts carrying their batch's reference and status`() {
        val overview = decode(
            MyOverviewDto.serializer(),
            """{"pc_claims":[{"id":"c1","batch_reference":"PC-B-7","description":"Gaffer tape",
                "settlement_type":"REDUCE_FLOAT","category":"materials","receipt_date":"1754000000000",
                "gross_amount":"12.50","currency":"EUR","status":"PENDING","batch_status":"QUERIED"}],
                "oop_claims":[{"id":"c2","batch_reference":"OOP-1","gross_amount":40,"created_at":1754000000000,
                "status":"POSTED"}],"floats":[]}""",
        ).toDomain()

        val claim = overview.pettyCashClaims.single()
        assertEquals("PC-B-7", claim.batchReference)
        assertEquals(12.5, claim.grossAmount)
        assertEquals("EUR", claim.currency)
        assertEquals(BatchStatus.Queried, claim.status, "the batch's status wins over the receipt's")
        assertEquals(1_754_000_000_000, claim.date)

        val oop = overview.outOfPocketClaims.single()
        assertEquals(40.0, oop.grossAmount)
        assertEquals(BatchStatus.Posted, oop.status, "the receipt's status when the batch's is missing")
        assertEquals(1_754_000_000_000, oop.date, "created_at when there is no receipt date")
    }

    // -- Department Overview: stats, oop_batches, spend_by_category -------------------

    @Test
    fun `the department overview reads the keys the web reads`() {
        val overview = decode(
            DepartmentOverviewDto.serializer(),
            """{"floats":[{"id":"f1","req_number":"PC-1","issued_float":"500","receipts_amount":"120"}],
                "oop_batches":[{"id":"b1","batch_reference":"OOP-9","total_gross":"80.00","status":"POSTED",
                  "settlement_details":"{\"payment_method\":\"BACS\"}"}],
                "stats":{"active_floats":2,"active_float_names":["u1","u2"],"cash_issued":"1500.00",
                  "receipts_approved":320.5,"oop_count":3,"oop_pending":1,"oop_approved":2},
                "spend_by_category":[{"category":"fuel","pct":60,"amount":"192.30"}]}""",
        ).toDomain("dept-7")

        assertEquals("dept-7", overview.departmentId)
        assertEquals(2, overview.stats.activeFloats)
        assertEquals(listOf("u1", "u2"), overview.stats.activeFloatHolders)
        assertEquals(1_500.0, overview.stats.cashIssued)
        assertEquals(320.5, overview.stats.receiptsApproved)
        assertEquals(3, overview.stats.outOfPocketCount)
        assertEquals(1, overview.stats.outOfPocketPending)
        assertEquals(2, overview.stats.outOfPocketApproved)
        assertEquals("BACS", overview.outOfPocketBatches.single().paymentMethod)
        assertEquals(80.0, overview.outOfPocketBatches.single().totalGross)
        assertEquals(60.0, overview.spendByCategory.single().percent)
        assertEquals(192.3, overview.spendByCategory.single().amount)
        assertEquals("PC-1", overview.floats.single().requestNumber)
    }

    @Test
    fun `the department overview asks for the viewer's own department`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val repository = FakeCash(writesSucceed = true).apply {
                    metadata = CashMetadata(isCoordinator = true)
                }
                val coordinator = CashViewer(
                    userId = "u1",
                    departmentIdentifier = "department_art",
                    designationIdentifier = null,
                    departmentId = "dept-art",
                )
                val vm = CashExpensesViewModel(repository = repository, viewer = { coordinator })
                vm.start()
                advanceUntilIdle()
                vm.onEvent(CashEvent.Open(CashDestination.DepartmentOverview))
                advanceUntilIdle()
                assertTrue("departmentOverview:dept-art" in repository.calls, repository.calls.toString())
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    // -- the parent chips ---------------------------------------------------------------

    @Test
    fun `the parent chips sum the web's fixed key lists`() {
        assertEquals(
            listOf(
                CashBadges.PC_POST_LEDGER,
                CashBadges.PC_SIGNOFF,
                CashBadges.PC_FLOAT,
                CashBadges.PC_TOPUPS,
                CashBadges.PC_RECON,
            ),
            parentBadgeKeys(ExpenseType.PettyCash, accountant = true),
        )
        assertEquals(
            listOf(CashBadges.PC_HISTORY, CashBadges.PC_FLOAT, CashBadges.CASH_EXTENSION),
            parentBadgeKeys(ExpenseType.PettyCash, accountant = false),
        )
        assertEquals(
            listOf(CashBadges.OOP_POST_LEDGER, CashBadges.OOP_SIGNOFF),
            parentBadgeKeys(ExpenseType.OutOfPocket, accountant = true),
        )
        assertEquals(listOf(CashBadges.OOP_HISTORY), parentBadgeKeys(ExpenseType.OutOfPocket, accountant = false))
    }

    // -- Settings: designation, not the team flag ---------------------------------------

    @Test
    fun `settings needs a senior designation, not the team's senior flag`() {
        val teamSenior = CashViewer(
            userId = "u1",
            departmentIdentifier = "department_accounts",
            designationIdentifier = "designation_assistant_accountant_accounts",
            metadata = CashMetadata(isSenior = true, requireSeniorSignOff = true),
        )
        assertFalse(teamSenior.canOpenSettings)
        assertFalse(CashDestination.Settings.visibleTo(teamSenior))
        assertTrue(teamSenior.canSeeSignOff, "the team flag still grants sign-off")

        val controller = teamSenior.copy(designationIdentifier = "designation_financial_controller_accounts")
        assertTrue(controller.canOpenSettings)
        assertFalse(controller.copy(enteredAsTool = true).canOpenSettings)
    }

    // -- the Action Queue -----------------------------------------------------------------

    @Test
    fun `the action queue lists only what is waiting, each pointing at its queue`() {
        val state = CashUiState(
            viewer = CashViewer(
                userId = "u",
                departmentIdentifier = "department_accounts",
                designationIdentifier = null,
            ),
            destination = CashDestination.PettyCashOverview,
        )
        val none = actionItems(state, CashStats(), CashSummary())
        assertTrue(none.isEmpty())

        val items = actionItems(
            state,
            CashStats(awaitingAudit = 1, readyToPost = 2, readyToPostAmount = 40.0, escalated = 3),
            CashSummary(oopBacsQueued = 10.0),
        )
        assertEquals(
            listOf(
                CashDestination.AuditQueue,
                CashDestination.PostLedger,
                CashDestination.PettyCashSignOff,
                CashDestination.PaymentRouting,
            ),
            items.map { it.target },
        )
        assertEquals("1 batch awaiting accounts audit", items[0].title)
        assertEquals("2 batches approved — ready to post", items[1].title)
    }

    @Test
    fun `age counts whole days and never goes negative`() {
        val day = 86_400_000L
        assertEquals(3, daysSince(1_000L, now = 1_000L + 3 * day + 5))
        assertEquals(0, daysSince(10 * day, now = day))
        assertEquals(0, daysSince(null))
    }
}
