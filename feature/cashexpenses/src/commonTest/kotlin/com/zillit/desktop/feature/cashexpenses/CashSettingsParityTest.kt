package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.cashexpenses.data.CashRepositoryImpl
import com.zillit.desktop.feature.cashexpenses.domain.AssigneeOption
import com.zillit.desktop.feature.cashexpenses.domain.CashAssignmentRule
import com.zillit.desktop.feature.cashexpenses.domain.CashDepartment
import com.zillit.desktop.feature.cashexpenses.domain.CashReferenceSources
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashSettingsSection
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.DeductionRule
import com.zillit.desktop.feature.cashexpenses.domain.DeductionRuleEdit
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentCoordinator
import com.zillit.desktop.feature.cashexpenses.domain.QuickCode
import com.zillit.desktop.feature.cashexpenses.domain.RuleProcess
import com.zillit.desktop.feature.cashexpenses.domain.RuleThreshold
import com.zillit.desktop.feature.cashexpenses.domain.commaList
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
import com.zillit.desktop.feature.cashexpenses.ui.SettingsEvent
import com.zillit.desktop.feature.cashexpenses.ui.TeamMemberDraft
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings saved as the web saves it: one section at a time with only its own
 * keys, coordinator rows checked before they go, rules edited in a dialog, a
 * saved assignment rule deleted the moment it is removed, and a background
 * reload that never throws away typing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashSettingsParityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val accountant = CashViewer(
        userId = "me",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant_accounts",
    )

    private val camera = CashDepartment(id = "d-cam", name = "Camera")
    private val art = CashDepartment(id = "d-art", name = "Art Department")

    private fun TestScope.openSettings(repository: FakeCash): CashExpensesViewModel =
        CashExpensesViewModel(
            repository = repository,
            viewer = { accountant },
            assignees = {
                listOf(
                    AssigneeOption(userId = "u-cam", fullName = "Cam Op", department = "Camera"),
                    AssigneeOption(userId = "u-art", fullName = "Art Dir", department = "Art Department"),
                )
            },
            reference = CashReferenceSources(departments = { listOf(camera, art) }),
        ).also {
            it.start()
            advanceUntilIdle()
            it.onEvent(CashEvent.Open(CashDestination.Settings))
            advanceUntilIdle()
        }

    // -- wire: one section, its keys alone ----------------------------------------------

    @Test
    fun `each section patches only its own keys, blank codes as null`() = runTest {
        val settings = CashSettings(
            custodianAccount = "1200",
            bsCodeFrom = "",
            bsCodeTo = " ",
            overrideFloatRequest = true,
            reimburseToPayroll = true,
            departmentCoordinators = listOf(DepartmentCoordinator("d-cam", listOf("u1"), true, false)),
            quickCodes = listOf(QuickCode(name = "Fuel", keywords = listOf("fuel"), vat = 20.0)),
        )
        val expected = mapOf(
            CashSettingsSection.Custodian to setOf("float_custodian_account", "bs_code_from", "bs_code_to"),
            CashSettingsSection.Coordinators to setOf("department_coordinators"),
            CashSettingsSection.Approval to setOf("approval_override"),
            CashSettingsSection.Reimbursement to setOf("reimburse_to_payroll"),
            CashSettingsSection.Deduction to setOf("deduction_rules"),
            CashSettingsSection.QuickCodes to setOf("quick_codes"),
        )
        expected.forEach { (section, keys) ->
            val (repo, sent) = wireRepository()
            repo.updateSettingsSection(section, settings)
            val request = sent.single()
            assertEquals("PATCH", request.first)
            assertTrue(request.second.endsWith("/api/v2/cash-expenses/settings"), request.second)
            assertEquals(keys, request.third.keys, "keys for $section")
        }

        val (repo, sent) = wireRepository()
        repo.updateSettingsSection(CashSettingsSection.Custodian, settings)
        val body = sent.single().third
        assertEquals("1200", body["float_custodian_account"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["bs_code_from"])
        assertEquals(JsonNull, body["bs_code_to"])

        val (coordRepo, coordSent) = wireRepository()
        coordRepo.updateSettingsSection(CashSettingsSection.Coordinators, settings)
        val row = coordSent.single().third["department_coordinators"]!!.jsonArray.single().jsonObject
        assertEquals("d-cam", row["department_id"]!!.jsonPrimitive.content)
        assertEquals("u1", row["user_ids"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("true", row["coding_required"]!!.jsonPrimitive.content)
        assertEquals("false", row["view_department_floats"]!!.jsonPrimitive.content)

        val (approvalRepo, approvalSent) = wireRepository()
        approvalRepo.updateSettingsSection(CashSettingsSection.Approval, settings)
        val flags = approvalSent.single().third["approval_override"]!!.jsonObject
        assertEquals(
            setOf("override_float_req", "override_receipt_batch", "require_coord_code", "require_senior_sign_off"),
            flags.keys,
        )
    }

    // -- per-section save -----------------------------------------------------------------

    @Test
    fun `saving one section leaves another section's unsaved edit in the draft`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply { settingsDoc = CashSettings() }
        val vm = openSettings(repository)

        val draft = vm.state.value.settingsDraft!!.copy(custodianAccount = "1200", reimburseToPayroll = true)
        vm.onEvent(CashEvent.EditSettings(draft))
        vm.onEvent(SettingsEvent.SaveSection(CashSettingsSection.Custodian))
        advanceUntilIdle()

        assertEquals(CashSettingsSection.Custodian, repository.lastSection?.first)
        val state = vm.state.value
        assertEquals("1200", state.settings?.custodianAccount)
        assertFalse(state.settings?.reimburseToPayroll == true, "the unsaved section did not go")
        assertTrue(state.settingsDraft?.reimburseToPayroll == true, "the unsaved edit is still there")
        assertTrue(CashSettingsSection.Reimbursement.isDirty(state.settingsDraft!!, state.settings))
        assertFalse(CashSettingsSection.Custodian.isDirty(state.settingsDraft!!, state.settings))
        assertNull(state.settingsUi.saving)
    }

    @Test
    fun `a background reload does not throw away typing`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply { settingsDoc = CashSettings() }
        val vm = openSettings(repository)
        vm.onEvent(CashEvent.EditSettings(vm.state.value.settingsDraft!!.copy(bsCodeFrom = "1000")))

        repository.settingsDoc = CashSettings(custodianAccount = "9999")
        vm.onEvent(CashEvent.Refresh)
        advanceUntilIdle()

        assertEquals("1000", vm.state.value.settingsDraft?.bsCodeFrom)

        // Nothing mid-edit: the reload lands.
        vm.onEvent(CashEvent.EditSettings(vm.state.value.settings!!))
        vm.onEvent(CashEvent.Refresh)
        advanceUntilIdle()
        assertEquals("9999", vm.state.value.settings?.custodianAccount)
    }

    // -- coordinators ----------------------------------------------------------------------

    @Test
    fun `an incomplete coordinator row is refused with its cells' errors`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply { settingsDoc = CashSettings() }
        val vm = openSettings(repository)

        vm.onEvent(SettingsEvent.AddCoordinator)
        vm.onEvent(SettingsEvent.SaveSection(CashSettingsSection.Coordinators))
        advanceUntilIdle()

        assertNull(repository.lastSection, "nothing is sent")
        assertEquals(setOf("0_dept", "0_users"), vm.state.value.settingsUi.coordErrors)

        // Opening the department cell clears its error.
        vm.onEvent(SettingsEvent.OpenCoordinatorPicker(0, users = false))
        assertEquals(setOf("0_users"), vm.state.value.settingsUi.coordErrors)
    }

    @Test
    fun `the pickers apply on Done, a new department empties the people`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply {
            settingsDoc = CashSettings(departmentCoordinators = listOf(DepartmentCoordinator("d-cam", listOf("u-cam"))))
        }
        val vm = openSettings(repository)
        assertEquals("d-cam", vm.state.value.assignees.first { it.userId == "u-cam" }.departmentId)

        vm.onEvent(SettingsEvent.AddCoordinator)
        vm.onEvent(SettingsEvent.OpenCoordinatorPicker(1, users = true))
        assertNull(vm.state.value.settingsUi.coordPicker, "no people picker before a department")

        vm.onEvent(SettingsEvent.OpenCoordinatorPicker(1, users = false))
        val picker = assertNotNull(vm.state.value.settingsUi.coordPicker)
        vm.onEvent(SettingsEvent.EditCoordinatorPicker(picker.copy(departmentId = "d-art")))
        assertEquals("", vm.state.value.settingsDraft!!.departmentCoordinators[1].departmentId, "buffered")
        vm.onEvent(SettingsEvent.ApplyCoordinatorPicker)
        assertEquals("d-art", vm.state.value.settingsDraft!!.departmentCoordinators[1].departmentId)

        vm.onEvent(SettingsEvent.OpenCoordinatorPicker(1, users = true))
        vm.onEvent(
            SettingsEvent.EditCoordinatorPicker(
                vm.state.value.settingsUi.coordPicker!!.copy(userIds = listOf("u-art")),
            ),
        )
        vm.onEvent(SettingsEvent.ApplyCoordinatorPicker)
        vm.onEvent(SettingsEvent.SaveSection(CashSettingsSection.Coordinators))
        advanceUntilIdle()

        val sent = repository.lastSection!!.second.departmentCoordinators
        assertEquals(listOf("d-cam", "d-art"), sent.map { it.departmentId })
        assertEquals(listOf("u-art"), sent[1].userIds)

        // Re-picking another department on the saved row drops its people.
        vm.onEvent(SettingsEvent.OpenCoordinatorPicker(0, users = false))
        val reopened = vm.state.value.settingsUi.coordPicker!!
        vm.onEvent(SettingsEvent.EditCoordinatorPicker(reopened.copy(departmentId = "x")))
        vm.onEvent(SettingsEvent.ApplyCoordinatorPicker)
        assertEquals(emptyList(), vm.state.value.settingsDraft!!.departmentCoordinators[0].userIds)
    }

    @Test
    fun `a department another row coordinates is not offered`() {
        val rows = listOf(DepartmentCoordinator("d-cam"), DepartmentCoordinator("d-art"))
        val offered = com.zillit.desktop.feature.cashexpenses.domain.CoordinatorRules
            .departmentsFor(1, rows, listOf(camera, art))
        assertEquals(listOf(art), offered)
    }

    // -- deduction rules -------------------------------------------------------------------

    @Test
    fun `a rule is added through the dialog, a shipped one cannot be deleted`() = runTest(dispatcher) {
        val shipped = DeductionRule(id = "fuel_deduction", title = "Fuel Deduction", systemDefault = true)
        val repository = FakeCash(writesSucceed = true).apply {
            settingsDoc = CashSettings(
                deductionRules = listOf(shipped),
                quickCodes = listOf(QuickCode(name = "Taxi", keywords = listOf("taxi", "uber"))),
            )
        }
        val vm = openSettings(repository)

        vm.onEvent(SettingsEvent.AddDeductionRule)
        var edit = assertNotNull(vm.state.value.settingsUi.ruleEditor)
        assertTrue(edit.isNew && edit.rule.enabled && edit.rule.type == "custom")
        vm.onEvent(SettingsEvent.CommitDeductionRule)
        assertNotNull(vm.state.value.settingsUi.ruleEditor, "a rule needs a title")

        edit = edit.copy(rule = edit.rule.copy(title = "Late travel")).withProcess(RuleProcess.SeniorReview)
        assertEquals(RuleThreshold.MinAmount, edit.rule.thresholdType, "review reads a minimum amount")
        edit = edit.withQuickCode(repository.settingsDoc!!.quickCodes.single())
        assertTrue(DeductionRuleEdit.alreadyAdded(edit.rule, repository.settingsDoc!!.quickCodes.single()))
        vm.onEvent(SettingsEvent.UpdateRuleEditor(edit))
        vm.onEvent(SettingsEvent.CommitDeductionRule)

        val rules = vm.state.value.settingsDraft!!.deductionRules
        assertEquals(listOf("Fuel Deduction", "Late travel"), rules.map { it.title })
        assertEquals(listOf("taxi", "uber"), rules[1].triggerCodes)

        vm.onEvent(SettingsEvent.RemoveDeductionRule(0))
        assertEquals(2, vm.state.value.settingsDraft!!.deductionRules.size, "the shipped rule stays")
        vm.onEvent(SettingsEvent.RemoveDeductionRule(1))
        assertEquals(1, vm.state.value.settingsDraft!!.deductionRules.size)
    }

    // -- team, assignment rules ---------------------------------------------------------------

    @Test
    fun `a cleared posting limit is unlimited, not zero`() = runTest(dispatcher) {
        val repository = FakeCash(writesSucceed = true).apply { settingsDoc = CashSettings() }
        val vm = openSettings(repository)

        vm.onEvent(CashEvent.EditTeamMember(TeamMemberDraft(userId = "u9", postingLimit = "  ")))
        vm.onEvent(CashEvent.SaveTeamMember)
        advanceUntilIdle()

        assertNull(repository.lastTeam?.single()?.postingLimit)
    }

    @Test
    fun `a saved assignment rule is deleted the moment it is removed`() = runTest(dispatcher) {
        val saved = CashAssignmentRule(id = "r1", assignTo = "me", persisted = true)
        val repository = FakeCash(writesSucceed = true).apply {
            settingsDoc = CashSettings(assignmentRules = listOf(saved))
        }
        val vm = openSettings(repository)

        vm.onEvent(SettingsEvent.AddAssignmentRule)
        assertEquals(2, vm.state.value.rulesDraft?.size)
        vm.onEvent(SettingsEvent.RemoveAssignmentRule(0))
        advanceUntilIdle()

        assertTrue("deleteRule:r1" in repository.calls)
        assertEquals(emptyList(), vm.state.value.settings?.assignmentRules)
        assertEquals(1, vm.state.value.rulesDraft?.size, "the unsaved new rule stays")
    }

    @Test
    fun `comma lists keep words, not the separators`() {
        assertEquals(listOf("fuel", "filling station"), "fuel, , filling station,".commaList())
    }

    // -- plumbing ----------------------------------------------------------------------------

    private fun wireRepository(): Pair<CashRepositoryImpl, List<Triple<String, String, JsonObject>>> {
        val sent = mutableListOf<Triple<String, String, JsonObject>>()
        val engine = MockEngine { request ->
            val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as? JsonObject }
            sent += Triple(request.method.value, request.url.toString(), body ?: JsonObject(emptyMap()))
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            respond("""{"status":1,"data":{}}""", HttpStatusCode.OK, json)
        }
        val repo = CashRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ SettingsMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
        return repo to sent
    }
}

private class SettingsMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
