package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.data.SettingsDto
import com.zillit.desktop.feature.cashexpenses.data.TeamMemberDto
import com.zillit.desktop.feature.cashexpenses.data.QuickCodeDto
import com.zillit.desktop.feature.cashexpenses.data.DeductionRuleDto
import com.zillit.desktop.feature.cashexpenses.domain.CashAccount
import com.zillit.desktop.feature.cashexpenses.domain.CashCompany
import com.zillit.desktop.feature.cashexpenses.domain.CashCountry
import com.zillit.desktop.feature.cashexpenses.domain.CashReferenceSources
import com.zillit.desktop.feature.cashexpenses.domain.CashVendor
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
import com.zillit.desktop.feature.cashexpenses.ui.pages.balanceSheetCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The low-severity gaps against the web: reference data a crew member's
 * pages need, settings rows written back whole, and the Settings pickers'
 * nominal subset.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashLowParityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val crew = CashViewer(
        userId = "u1",
        departmentIdentifier = "department_camera",
        designationIdentifier = null,
        departmentId = "d-camera",
    )

    private val accountant = CashViewer(
        userId = "me",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
    )

    private fun TestScope.viewModel(
        repository: FakeCash,
        reference: CashReferenceSources = CashReferenceSources(),
        viewer: CashViewer = crew,
    ) = CashExpensesViewModel(repository = repository, viewer = { viewer }, reference = reference).also {
        it.start()
        advanceUntilIdle()
    }

    // -- companies on mount ---------------------------------------------------------------

    @Test
    fun `Active Floats reads the companies without a dialog, once`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            companyRows = listOf(CashCompany("c1", "Acme Films"))
            activeFloatRows = listOf(float("f1"))
        }
        val vm = viewModel(repository, viewer = accountant)
        assertTrue(vm.state.value.companies.isEmpty(), "nothing asks for them on the landing page")

        vm.onEvent(CashEvent.Open(CashDestination.ActiveFloats))
        advanceUntilIdle()
        assertEquals(listOf("Acme Films"), vm.state.value.companies.map { it.name })

        vm.onEvent(CashEvent.OpenFloatDetail("f1"))
        advanceUntilIdle()
        assertEquals(1, repository.calls.count { it == "companies" }, "the float details dialog reuses them")
    }

    @Test
    fun `the float details dialog reads the companies on its own too`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            companyRows = listOf(CashCompany("c1", "Acme Films"))
            activeFloatRows = listOf(float("f1"))
        }
        val vm = viewModel(repository)
        vm.onEvent(CashEvent.OpenFloatDetail("f1"))
        advanceUntilIdle()
        assertEquals("c1", vm.state.value.companies.single().id)
    }

    // -- vendor and country pickers -------------------------------------------------------

    @Test
    fun `the float request reads the vendors and countries its custom fields pick from`() = runTest(dispatcher) {
        val reference = CashReferenceSources(
            vendors = { listOf(CashVendor("v1", "Panavision")) },
            countries = { listOf(CashCountry("GB", "United Kingdom")) },
        )
        val vm = viewModel(FakeCash(writesSucceed = true), reference)
        assertTrue(vm.state.value.vendors.isEmpty())

        vm.onEvent(CashEvent.Open(CashDestination.FloatRequest))
        advanceUntilIdle()

        assertEquals("Panavision", vm.state.value.vendors.single().name)
        assertEquals("GB", vm.state.value.countries.single().code)
    }

    // -- settings rows round-trip ---------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `team members, quick codes and rules keep the keys this client does not model`() {
        val settings = json.decodeFromString(
            SettingsDto.serializer(),
            """
            {
              "team_members": [
                {"user_id": "u9", "posting_limit": 50, "can_override": false, "is_senior": false,
                 "added_by": "u1", "joined": 1700000000000}
              ],
              "quick_codes": [
                {"name": "Fuel", "nominal_code": "2400", "keywords": ["fuel"], "vat": 20, "colour": "amber"}
              ],
              "deduction_rules": [
                {"id": "fuel", "type": "fuel", "title": "Fuel", "enable": true, "system_default": true,
                 "audit": {"by": "u1"}}
              ]
            }
            """.trimIndent(),
        ).toDomain()

        val member = TeamMemberDto.of(settings.teamMembers.single())
        assertEquals("u1", (member["added_by"] as JsonPrimitive).content)
        assertEquals("u9", (member["user_id"] as JsonPrimitive).content)

        val code = QuickCodeDto.of(settings.quickCodes.single())
        assertEquals("amber", (code["colour"] as JsonPrimitive).content)

        val rule = DeductionRuleDto.of(settings.deductionRules.single())
        assertEquals("u1", rule["audit"]?.jsonObject?.get("by")?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `an edit to a stored member is written over the stored row, not a rebuilt one`() {
        val stored = json.decodeFromString(
            SettingsDto.serializer(),
            """{"team_members": [{"user_id": "u9", "posting_limit": 50, "added_by": "u1"}]}""",
        ).toDomain().teamMembers.single()

        val edited = TeamMemberDto.of(stored.copy(postingLimit = 75.0))
        assertEquals("u1", (edited["added_by"] as JsonPrimitive).content)
        assertEquals("75.0", (edited["posting_limit"] as JsonPrimitive).content)
    }

    // -- the Settings pickers' nominal subset ---------------------------------------------

    @Test
    fun `the balance-sheet fields offer nominals and codes, not a header or a nominal with codes under it`() {
        val chart = listOf(
            CashAccount("1000", "Bank", balanceSheet = true, nominal = true),
            CashAccount("1100", "Cash group", balanceSheet = true, nominal = false),
            CashAccount("1100-10", "Petty cash", balanceSheet = true, nominal = true),
            CashAccount("5000", "Costs", balanceSheet = false, nominal = true),
        )
        assertEquals(listOf("1000", "1100-10"), chart.balanceSheetCodes().map { it.code })
        // A chart that marks nothing balance-sheet still offers something.
        assertEquals(chart.size, chart.map { it.copy(balanceSheet = false) }.balanceSheetCodes().size)
    }

    private fun float(id: String) = com.zillit.desktop.feature.cashexpenses.domain.CashFloat(
        id = id, requestNumber = "PC-$id", userId = "u1", holderName = "", departmentId = null,
        status = FloatStatus.AwaitingApproval, currency = "GBP", requestedAmount = 100.0, issuedAmount = 100.0,
        balance = 40.0, receiptsAmount = 0.0, receiptsCommits = null, returnAmount = 0.0, bsCode = null,
        companyId = "c1", duration = null, durationType = null, purpose = null, createdAt = 1,
    )
}
