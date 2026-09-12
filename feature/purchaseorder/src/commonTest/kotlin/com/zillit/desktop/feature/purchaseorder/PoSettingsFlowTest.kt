package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.purchaseorder.domain.AssetFilters
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoAssignmentRule
import com.zillit.desktop.feature.purchaseorder.domain.PoDepartment
import com.zillit.desktop.feature.purchaseorder.domain.PoDescriptionFormat
import com.zillit.desktop.feature.purchaseorder.domain.PoHistoryEntry
import com.zillit.desktop.feature.purchaseorder.domain.PoNominal
import com.zillit.desktop.feature.purchaseorder.domain.PoRefresh
import com.zillit.desktop.feature.purchaseorder.domain.PoSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoSettingsBundle
import com.zillit.desktop.feature.purchaseorder.domain.PoSettingsPeople
import com.zillit.desktop.feature.purchaseorder.domain.PoSplitType
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoTeamMember
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.domain.Vendor
import com.zillit.desktop.feature.purchaseorder.ui.PoConfirmAction
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.feature.purchaseorder.ui.PoEffect
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoPrompt
import com.zillit.desktop.feature.purchaseorder.ui.PoSettingsActions
import com.zillit.desktop.feature.purchaseorder.ui.PoSettingsSection
import com.zillit.desktop.feature.purchaseorder.ui.PurchaseOrderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Settings tab's flows, against a fake service — the web's `POSettings`
 * behaviour: a card is dirty once edited and clean once saved, "Saved" fades,
 * rules are created or patched row by row, and a stored rule asks before it goes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoSettingsFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val senior = PoViewer("u1", "department_accounts", "designation_production_accountant_accounts")

    private val people = object : PoSettingsPeople {
        override suspend fun team() = listOf(PoTeamMember("u1", "Jane Smith", "Production Accountant"))
        override suspend fun departments() = listOf(PoDepartment("d1", "Camera"))
    }

    private fun TestScope.open(repository: FakeSettings): PurchaseOrderViewModel {
        val model = PurchaseOrderViewModel(repository, { senior }, offline = null, people = people)
        model.start()
        runCurrent()
        model.onEvent(PoEvent.Open(PoDestination.Settings))
        runCurrent()
        return model
    }

    @Test
    fun `opening the tab reads the document, the rules and the pickers' lists`() = runTest(dispatcher) {
        val repository = FakeSettings()
        val model = open(repository)
        val settings = model.state.value.settings
        assertFalse(settings.loading)
        assertEquals("QW", settings.saved.numberPrefix)
        assertEquals(1, settings.rules.size)
        assertEquals("Jane Smith", settings.team.single().name)
        assertEquals("Camera", settings.departments.single().name)
        assertEquals("2400", settings.nominals.single().code)
        assertEquals(listOf("Camera"), settings.tags)
        assertFalse(settings.isDirty(PoSettingsSection.Numbering))
    }

    @Test
    fun `a card is dirty once edited, clean once saved, and says Saved for a moment`() = runTest(dispatcher) {
        val repository = FakeSettings()
        val model = open(repository)
        val edited = model.state.value.settings.edited.copy(descriptionFormat = PoDescriptionFormat.ItemDayMonth)
        model.onEvent(PoEvent.EditSettings(edited))
        assertTrue(model.state.value.settings.isDirty(PoSettingsSection.Description))
        assertFalse(model.state.value.settings.isDirty(PoSettingsSection.Rental), "another card's flag is its own")

        model.onEvent(PoEvent.SaveSettings(PoSettingsSection.Description))
        runCurrent()
        assertEquals(PoDescriptionFormat.ItemDayMonth, repository.savedFormat)
        assertFalse(model.state.value.settings.isDirty(PoSettingsSection.Description))
        assertTrue(PoSettingsSection.Description in model.state.value.settings.justSaved)

        advanceTimeBy(PoSettingsActions.SAVED_FLASH_MILLIS + 1)
        runCurrent()
        assertTrue(model.state.value.settings.justSaved.isEmpty(), "the tick fades")
    }

    @Test
    fun `an invalid asset rule is refused before anything is sent`() = runTest(dispatcher) {
        val repository = FakeSettings()
        val model = open(repository)
        val edited = model.state.value.settings.edited
        val backwards = AssetFilters(priceLow = "200", priceHigh = "100")
        model.onEvent(PoEvent.EditSettings(edited.copy(assetFilters = backwards)))
        model.onEvent(PoEvent.SaveSettings(PoSettingsSection.Asset))
        runCurrent()
        assertNull(repository.savedFilters)
        assertTrue(model.state.value.settings.isDirty(PoSettingsSection.Asset))
    }

    @Test
    fun `saving the rules patches stored rows and creates new ones, in order`() = runTest(dispatcher) {
        val repository = FakeSettings()
        val model = open(repository)
        model.onEvent(PoEvent.AddRule)
        val added = model.state.value.settings.rules.last()
        assertFalse(added.persisted)
        assertEquals("u1", added.assignTo, "a new rule is assigned to the first of the team")
        assertTrue(model.state.value.settings.isDirty(PoSettingsSection.Rules))

        model.onEvent(PoEvent.EditRule(added.copy(departments = listOf("d1"), amountMin = "250")))
        model.onEvent(PoEvent.SaveSettings(PoSettingsSection.Rules))
        runCurrent()

        assertEquals(listOf("r1"), repository.updated)
        assertEquals(1, repository.created.size)
        assertEquals(listOf("d1"), repository.created.single().departments)
        assertTrue(model.state.value.settings.rules.all { it.persisted })
        assertFalse(model.state.value.settings.isDirty(PoSettingsSection.Rules))
    }

    /** A row the server took keeps the id it was given, so a retry patches it rather than making a second. */
    @Test
    fun `a rule that fails mid-save leaves the rows before it saved`() = runTest(dispatcher) {
        val repository = FakeSettings(failCreateAfter = 1)
        val model = open(repository)
        model.onEvent(PoEvent.AddRule)
        model.onEvent(PoEvent.AddRule)
        model.onEvent(PoEvent.SaveSettings(PoSettingsSection.Rules))
        runCurrent()

        val rules = model.state.value.settings.rules
        assertEquals(2, repository.created.size, "the second create was attempted and refused")
        assertEquals(listOf(true, true, false), rules.map { it.persisted })
        assertTrue(model.state.value.settings.isDirty(PoSettingsSection.Rules), "the unsaved row keeps the card dirty")

        repository.failCreateAfter = null
        model.onEvent(PoEvent.SaveSettings(PoSettingsSection.Rules))
        runCurrent()
        assertEquals(3, repository.created.size, "only the row that never landed is created again")
        assertEquals(
            listOf("r1", "r1", "new-1"),
            repository.updated,
            "the row that did land is patched on the retry, not created a second time",
        )
    }

    @Test
    fun `a stored rule asks before it goes, one never saved just goes`() = runTest(dispatcher) {
        val repository = FakeSettings()
        val model = open(repository)
        model.onEvent(PoEvent.AddRule)
        val local = model.state.value.settings.rules.last()
        model.onEvent(PoEvent.RemoveRule(local.id))
        assertEquals(1, model.state.value.settings.rules.size, "gone without a prompt")
        assertNull(model.state.value.prompt)

        model.onEvent(PoEvent.RemoveRule("r1"))
        val prompt = model.state.value.prompt as PoPrompt.Confirm
        assertEquals(PoConfirmAction.RemoveRule, prompt.action)
        assertEquals("Remove Assignment Rule", prompt.title)
        assertTrue(prompt.message.contains("1 nominal → assign to Jane Smith (Production Accountant)"))
        assertTrue(model.state.value.settings.rules.any { it.id == "r1" }, "still there until confirmed")

        model.onEvent(PoEvent.ConfirmPrompt)
        runCurrent()
        assertEquals(listOf("r1"), repository.deleted)
        assertTrue(model.state.value.settings.rules.isEmpty())
    }

    @Test
    fun `the Form Configuration card hands off to the hub`() = runTest(dispatcher) {
        val model = open(FakeSettings())
        val effects = mutableListOf<PoEffect>()
        val job = launch { model.effects.collect { effects += it } }
        runCurrent()
        model.onEvent(PoEvent.OpenFormConfiguration)
        runCurrent()
        assertEquals(listOf<PoEffect>(PoEffect.OpenFormConfig), effects)
        job.cancel()
    }

    /** Enough of the service for the Settings tab: everything else answers a refusal. */
    private class FakeSettings(var failCreateAfter: Int? = null) : PurchaseOrderRepository {
        override val refreshes: Flow<PoRefresh> = emptyFlow()
        var savedFormat: PoDescriptionFormat? = null
        var savedFilters: AssetFilters? = null
        val updated = mutableListOf<String>()
        val created = mutableListOf<PoAssignmentRule>()
        val deleted = mutableListOf<String>()
        private var minted = 0

        private val stored = PoSettings(numberPrefix = "QW", splitType = PoSplitType.Daily)
        private val rule = PoAssignmentRule(id = "r1", nominalCodes = listOf("2400"), assignTo = "u1", persisted = true)

        override suspend fun settings() = ZillitResult.Success(PoSettingsBundle(stored, listOf(rule)))
        override suspend fun saveDescriptionFormat(format: PoDescriptionFormat): ZillitResult<PoSettings> {
            savedFormat = format
            return ZillitResult.Success(stored.copy(descriptionFormat = format))
        }
        override suspend fun saveAssetFilters(filters: AssetFilters): ZillitResult<PoSettings> {
            savedFilters = filters
            return ZillitResult.Success(stored.copy(assetFilters = filters))
        }
        override suspend fun createRule(rule: PoAssignmentRule): ZillitResult<PoAssignmentRule> {
            created += rule
            failCreateAfter?.let { limit ->
                if (created.size > limit) return ZillitResult.Failure(ZillitError.Unknown("refused"))
            }
            return ZillitResult.Success(rule.copy(id = "new-${++minted}", persisted = true))
        }
        override suspend fun updateRule(rule: PoAssignmentRule): ZillitResult<PoAssignmentRule> {
            updated += rule.id
            return ZillitResult.Success(rule)
        }
        override suspend fun deleteRule(id: String): ZillitResult<Unit> {
            deleted += id
            return ZillitResult.Success(Unit)
        }
        override suspend fun nominalCodes() = ZillitResult.Success(listOf(PoNominal("2400", "Equipment Hire")))
        override suspend fun assetTags() = ZillitResult.Success(listOf("Camera"))

        override suspend fun orders(status: PoStatus?, departmentId: String?): ZillitResult<List<PurchaseOrder>> =
            ZillitResult.Success(emptyList())
        override suspend fun approvalQueue(): ZillitResult<List<PurchaseOrder>> = ZillitResult.Success(emptyList())
        override suspend fun myOrders(): ZillitResult<List<PurchaseOrder>> = ZillitResult.Success(emptyList())
        override suspend fun vendors(): ZillitResult<List<Vendor>> = ZillitResult.Success(emptyList())
        override suspend fun order(id: String): ZillitResult<PurchaseOrder> = unsupported()
        override suspend fun history(id: String): ZillitResult<List<PoHistoryEntry>> =
            ZillitResult.Success(emptyList())
        override suspend fun create(order: NewPurchaseOrder): ZillitResult<Unit> = unsupported()
        override suspend fun update(id: String, order: NewPurchaseOrder): ZillitResult<Unit> = unsupported()
        override suspend fun delete(id: String): ZillitResult<Unit> = unsupported()
        override suspend fun approve(id: String, note: String?): ZillitResult<Unit> = unsupported()
        override suspend fun reject(id: String, reason: String): ZillitResult<Unit> = unsupported()
        override suspend fun post(id: String, note: String?): ZillitResult<Unit> = unsupported()
        override suspend fun close(id: String, note: String?): ZillitResult<Unit> = unsupported()
        override suspend fun closeAll(ids: List<String>, effectiveDate: Long?): ZillitResult<Unit> = unsupported()

        private fun <T> unsupported(): ZillitResult<T> = ZillitResult.Failure(ZillitError.Unknown("not in this test"))
    }
}
