package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.settings.admin.domain.AccessType
import com.zillit.desktop.feature.settings.admin.domain.AdminRepository
import com.zillit.desktop.feature.settings.admin.domain.AdminUnit
import com.zillit.desktop.feature.settings.admin.domain.CompanyDetails
import com.zillit.desktop.feature.settings.admin.domain.CrewMember
import com.zillit.desktop.feature.settings.admin.domain.CrewStatus
import com.zillit.desktop.feature.settings.admin.domain.Department
import com.zillit.desktop.feature.settings.admin.domain.JobTitle
import com.zillit.desktop.feature.settings.admin.domain.NewPreApproval
import com.zillit.desktop.feature.settings.admin.domain.NewSosRecipient
import com.zillit.desktop.feature.settings.admin.domain.PreApprovedCrew
import com.zillit.desktop.feature.settings.admin.domain.ProductionTool
import com.zillit.desktop.feature.settings.admin.domain.RightsChange
import com.zillit.desktop.feature.settings.admin.domain.RightsSection
import com.zillit.desktop.feature.settings.admin.domain.SosRecipient
import com.zillit.desktop.feature.settings.admin.domain.ToolGroup
import com.zillit.desktop.feature.settings.admin.domain.ToolRights
import com.zillit.desktop.feature.settings.admin.domain.UnitKind
import com.zillit.desktop.feature.settings.admin.ui.AdminConfirmation
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminEvent
import com.zillit.desktop.feature.settings.admin.ui.AdminField
import com.zillit.desktop.feature.settings.admin.ui.AdminForm
import com.zillit.desktop.feature.settings.admin.ui.AdminViewModel
import com.zillit.desktop.feature.settings.admin.ui.NameKind
import com.zillit.desktop.feature.settings.admin.ui.RightsToggle
import com.zillit.desktop.feature.settings.admin.ui.moved
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The administration pages' behaviour, against a recording repository.
 *
 * Two rules carry real consequence and both are asserted here: **nothing is
 * applied optimistically** — every mutation is followed by a re-read, because
 * the server can refuse for reasons this client cannot see — and **a rejected
 * form keeps what was typed**, because retyping a department name that was
 * rejected as a duplicate is how an admin gives up and asks someone else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Records what was called, and answers whatever the test set up. */
    private class Recorder : AdminRepository {
        val calls = mutableListOf<String>()

        var departmentsAnswer: ZillitResult<List<Department>> = ZillitResult.Success(
            listOf(
                Department("d1", "Art", jobTitles = listOf(JobTitle("r1", "Standby Art"))),
                Department("d2", "Camera"),
                Department("d3", "Transportation"),
            ),
        )
        var crewAnswer: ZillitResult<List<CrewMember>> = ZillitResult.Success(
            listOf(
                CrewMember("u1", "Ada Lovelace", deviceId = "dev-1"),
                CrewMember("u2", "Grace Hopper", deviceId = "dev-2", isAdmin = true),
            ),
        )
        var rightsAnswer: ZillitResult<List<ToolRights>> = ZillitResult.Success(
            listOf(
                ToolRights(
                    toolIdentifier = "budget_tool",
                    toolName = "Budget",
                    unitId = "unit-1",
                    section = RightsSection.Tools,
                    canView = true,
                    canPost = true,
                    canDownload = true,
                ),
            ),
        )

        /** What every mutation answers. Set to a failure to test the sad path. */
        var mutationAnswer: ZillitResult<Unit> = ZillitResult.Success(Unit)

        val rightsChanges = mutableListOf<RightsChange>()

        private fun record(name: String): ZillitResult<Unit> {
            calls += name
            return mutationAnswer
        }

        override suspend fun departments(): ZillitResult<List<Department>> {
            calls += "departments"
            return departmentsAnswer
        }

        override suspend fun createDepartment(name: String) = record("createDepartment:$name")
        override suspend fun deleteDepartment(departmentId: String) = record("deleteDepartment:$departmentId")
        override suspend fun reorderDepartments(departmentIds: List<String>) =
            record("reorder:${departmentIds.joinToString(",")}")

        override suspend fun createJobTitle(departmentId: String, name: String) =
            record("createJobTitle:$departmentId:$name")

        override suspend fun deleteJobTitle(departmentId: String, jobTitleId: String) =
            record("deleteJobTitle:$departmentId:$jobTitleId")

        override suspend fun crew(): ZillitResult<List<CrewMember>> {
            calls += "crew"
            return crewAnswer
        }

        override suspend fun setAdminAccess(userId: String, isAdmin: Boolean) =
            record("setAdminAccess:$userId:$isAdmin")

        override suspend fun setCrewStatus(userId: String, deviceId: String, status: CrewStatus) =
            record("setCrewStatus:$userId:${status.wire}")

        override suspend fun preApproved(): ZillitResult<List<PreApprovedCrew>> {
            calls += "preApproved"
            return ZillitResult.Success(emptyList())
        }

        override suspend fun addPreApproved(request: NewPreApproval) =
            record("addPreApproved:${request.firstName}")

        override suspend fun tools(includeAlwaysOn: Boolean): ZillitResult<List<ProductionTool>> {
            calls += "tools:$includeAlwaysOn"
            return ZillitResult.Success(listOf(ProductionTool("casting_tool", "Casting", enabled = true)))
        }

        override suspend fun setToolsEnabled(tools: List<ProductionTool>) =
            record("setToolsEnabled:${tools.count { it.enabled }}")

        override suspend fun toolGroups(): ZillitResult<List<ToolGroup>> {
            calls += "toolGroups"
            return ZillitResult.Success(emptyList())
        }

        override suspend fun createToolGroup(name: String) = record("createToolGroup:$name")
        override suspend fun renameToolGroup(toolGroupId: String, name: String) =
            record("renameToolGroup:$toolGroupId:$name")

        override suspend fun deleteToolGroup(toolGroupId: String) = record("deleteToolGroup:$toolGroupId")
        override suspend fun moveTool(identifier: String, groupIdentifier: String) =
            record("moveTool:$identifier:$groupIdentifier")

        override suspend fun renameProduction(name: String) = record("renameProduction:$name")

        override suspend fun companyDetails(): ZillitResult<CompanyDetails> {
            calls += "companyDetails"
            return ZillitResult.Success(CompanyDetails(name = "Zillit Films"))
        }

        override suspend fun saveCompanyDetails(details: CompanyDetails) =
            record("saveCompanyDetails:${details.name}")

        override suspend fun clearCompanyLogo() = record("clearCompanyLogo")

        override suspend fun watermarkUrl(): ZillitResult<String?> {
            calls += "watermarkUrl"
            return ZillitResult.Success(null)
        }

        override suspend fun clearWatermark() = record("clearWatermark")

        override suspend fun sosRecipients(): ZillitResult<List<SosRecipient>> {
            calls += "sosRecipients"
            return ZillitResult.Success(emptyList())
        }

        override suspend fun addSosRecipient(request: NewSosRecipient) = record("addSosRecipient")
        override suspend fun removeSosRecipient(recipientId: String) = record("removeSos:$recipientId")

        override suspend fun units(kind: UnitKind): ZillitResult<List<AdminUnit>> {
            calls += "units:$kind"
            return ZillitResult.Success(emptyList())
        }

        override suspend fun createUnit(kind: UnitKind, name: String) = record("createUnit:$kind:$name")
        override suspend fun renameUnit(kind: UnitKind, unitId: String, name: String) =
            record("renameUnit:$kind:$unitId:$name")

        override suspend fun deleteUnit(kind: UnitKind, unitId: String) = record("deleteUnit:$kind:$unitId")
        override suspend fun setUnitEnabled(unitId: String, enabled: Boolean) =
            record("setUnitEnabled:$unitId:$enabled")

        override suspend fun rights(userId: String): ZillitResult<List<ToolRights>> {
            calls += "rights:$userId"
            return rightsAnswer
        }

        override suspend fun changeRights(change: RightsChange): ZillitResult<Unit> {
            rightsChanges += change
            return record("changeRights:${change.access.wire}:${change.enable}")
        }

        override suspend fun scheduleDeletion(hours: Int) = record("scheduleDeletion:$hours")
        override suspend fun cancelDeletion() = record("cancelDeletion")
    }

    private fun viewModel(repository: Recorder = Recorder()) =
        AdminViewModel(repository, productionName = { "Feature One" })

    // -- loading -----------------------------------------------------------

    @Test
    fun `opening a page reads its list once`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.Departments))
        advanceUntilIdle()
        model.onEvent(AdminEvent.Opened(AdminDestination.Departments))
        advanceUntilIdle()

        assertEquals(listOf("departments"), repository.calls)
        assertEquals(3, model.state.value.departments.size)
        assertTrue(model.state.value.hasLoaded)
    }

    @Test
    fun `a failed load keeps what was already listed`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.Departments))
        advanceUntilIdle()

        repository.departmentsAnswer = ZillitResult.Failure(ZillitError.NoConnection())
        model.onEvent(AdminEvent.Refresh)
        advanceUntilIdle()

        // A dropped request is not evidence the production lost its departments.
        assertEquals(3, model.state.value.departments.size)
        assertNotNull(model.state.value.error)
    }

    @Test
    fun `moving between pages clears the search and the selection`() = runTest {
        val model = viewModel()

        model.onEvent(AdminEvent.Opened(AdminDestination.Departments))
        advanceUntilIdle()
        model.onEvent(AdminEvent.SearchChanged("camera"))
        model.onEvent(AdminEvent.SelectDepartment("d2"))
        model.onEvent(AdminEvent.Opened(AdminDestination.Crew))
        advanceUntilIdle()

        assertEquals("", model.state.value.query)
        assertNull(model.state.value.selection.departmentId)
    }

    // -- nothing is optimistic ---------------------------------------------

    @Test
    fun `a mutation is followed by a fresh read`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.Departments))
        advanceUntilIdle()
        model.onEvent(AdminEvent.OpenName(NameKind.Department))
        model.onEvent(AdminEvent.FieldChanged(AdminField.Name, "Camera"))
        model.onEvent(AdminEvent.SubmitForm)
        advanceUntilIdle()

        assertEquals(
            listOf("departments", "createDepartment:Camera", "departments"),
            repository.calls,
        )
    }

    @Test
    fun `a successful mutation closes the form and says what happened`() = runTest {
        val model = viewModel()

        model.onEvent(AdminEvent.Opened(AdminDestination.Departments))
        advanceUntilIdle()
        model.onEvent(AdminEvent.OpenName(NameKind.Department))
        model.onEvent(AdminEvent.FieldChanged(AdminField.Name, "Camera"))
        model.onEvent(AdminEvent.SubmitForm)
        advanceUntilIdle()

        assertNull(model.state.value.form)
        assertEquals("“Camera” added.", model.state.value.outcome)
    }

    /**
     * The duplicate-name case, which is the one an admin actually hits.
     *
     * The server refuses with a translation key on an HTTP 200; the form has to
     * stay open with the name still in it.
     */
    @Test
    fun `a rejected mutation keeps the form and what was typed`() = runTest {
        val repository = Recorder()
        repository.mutationAnswer =
            ZillitResult.Failure(ZillitError.Validation("That department already exists."))
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.Departments))
        advanceUntilIdle()
        model.onEvent(AdminEvent.OpenName(NameKind.Department))
        model.onEvent(AdminEvent.FieldChanged(AdminField.Name, "Camera"))
        model.onEvent(AdminEvent.SubmitForm)
        advanceUntilIdle()

        val form = model.state.value.form as? AdminForm.Name
        assertNotNull(form)
        assertEquals("Camera", form.value)
        assertEquals("That department already exists.", form.error)
        // And no reload: nothing changed, so there is nothing to re-read.
        assertEquals(listOf("departments", "createDepartment:Camera"), repository.calls)
    }

    @Test
    fun `a name under three characters is refused without a call`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.Departments))
        advanceUntilIdle()
        model.onEvent(AdminEvent.OpenName(NameKind.Department))
        model.onEvent(AdminEvent.FieldChanged(AdminField.Name, "Ca"))
        model.onEvent(AdminEvent.SubmitForm)
        advanceUntilIdle()

        assertEquals(listOf("departments"), repository.calls)
        assertNotNull((model.state.value.form as? AdminForm.Name)?.error)
    }

    // -- confirmations --------------------------------------------------------

    @Test
    fun `granting admin asks first and revoking does not`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.Crew))
        advanceUntilIdle()

        model.onEvent(AdminEvent.AdminAccessChanged("u1", true))
        advanceUntilIdle()
        // Nothing sent yet — the dialog is up.
        assertEquals(listOf("crew"), repository.calls)
        assertTrue(model.state.value.confirming is AdminConfirmation.GrantAdmin)

        model.onEvent(AdminEvent.ConfirmAction)
        advanceUntilIdle()
        assertTrue(repository.calls.contains("setAdminAccess:u1:true"))

        model.onEvent(AdminEvent.AdminAccessChanged("u2", false))
        advanceUntilIdle()
        assertTrue(repository.calls.contains("setAdminAccess:u2:false"))
        assertNull(model.state.value.confirming)
    }

    @Test
    fun `removing someone from the crew asks first and restoring does not`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.Crew))
        advanceUntilIdle()

        model.onEvent(AdminEvent.CrewActiveChanged("u1", false))
        advanceUntilIdle()
        assertTrue(model.state.value.confirming is AdminConfirmation.RemoveFromCrew)
        assertFalse(repository.calls.any { it.startsWith("setCrewStatus") })

        model.onEvent(AdminEvent.ConfirmAction)
        advanceUntilIdle()
        assertTrue(repository.calls.contains("setCrewStatus:u1:removed"))
    }

    @Test
    fun `dismissing a confirmation does nothing at all`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.Crew))
        advanceUntilIdle()
        model.onEvent(AdminEvent.Ask(AdminConfirmation.RemoveFromCrew("u1", "dev-1", "Ada")))
        model.onEvent(AdminEvent.DismissConfirmation)
        advanceUntilIdle()

        assertEquals(listOf("crew"), repository.calls)
        assertNull(model.state.value.confirming)
    }

    // -- rights ------------------------------------------------------------------

    @Test
    fun `picking someone reads their rights`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.Rights))
        advanceUntilIdle()
        model.onEvent(AdminEvent.SelectCrew("u1"))
        advanceUntilIdle()

        assertEquals(listOf("crew", "rights:u1"), repository.calls)
        assertEquals(1, model.state.value.selection.rights.size)
    }

    /**
     * One click, three calls, then a re-read.
     *
     * Revoking view has to take posting and downloading with it, and the server
     * does none of that.
     */
    @Test
    fun `revoking view sends the whole cascade and re-reads`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.Rights))
        advanceUntilIdle()
        model.onEvent(AdminEvent.SelectCrew("u1"))
        advanceUntilIdle()
        repository.calls.clear()

        model.onEvent(
            AdminEvent.RightsToggled(
                RightsToggle("budget_tool", RightsSection.Tools, AccessType.View, enable = false),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf(AccessType.Download, AccessType.Post, AccessType.View),
            repository.rightsChanges.map { it.access },
        )
        assertEquals("rights:u1", repository.calls.last())
    }

    /** A right the server says is not ours to change is not sent. */
    @Test
    fun `a locked right sends nothing`() = runTest {
        val repository = Recorder()
        repository.rightsAnswer = ZillitResult.Success(
            listOf(
                ToolRights(
                    toolIdentifier = "budget_tool",
                    toolName = "Budget",
                    unitId = "unit-1",
                    section = RightsSection.Tools,
                    canView = true,
                    postLocked = true,
                ),
            ),
        )
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.Rights))
        advanceUntilIdle()
        model.onEvent(AdminEvent.SelectCrew("u1"))
        advanceUntilIdle()
        repository.calls.clear()

        model.onEvent(
            AdminEvent.RightsToggled(
                RightsToggle("budget_tool", RightsSection.Tools, AccessType.Post, enable = true),
            ),
        )
        advanceUntilIdle()

        assertTrue(repository.rightsChanges.isEmpty())
        assertTrue(repository.calls.isEmpty())
    }

    // -- reordering ------------------------------------------------------------------

    @Test
    fun `reordering is local until it is saved`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.CrewOrder))
        advanceUntilIdle()
        model.onEvent(AdminEvent.MoveDepartment("d3", -1))
        advanceUntilIdle()

        assertEquals(listOf("departments"), repository.calls)
        assertEquals(listOf("d1", "d3", "d2"), model.state.value.selection.order.map { it.id })
        assertTrue(model.state.value.selection.isReordered(model.state.value.departments))

        model.onEvent(AdminEvent.SaveOrder)
        advanceUntilIdle()

        assertEquals("reorder:d1,d3,d2", repository.calls[1])
    }

    @Test
    fun `resetting puts the order back to what the server said`() = runTest {
        val model = viewModel()

        model.onEvent(AdminEvent.Opened(AdminDestination.CrewOrder))
        advanceUntilIdle()
        model.onEvent(AdminEvent.MoveDepartment("d3", -1))
        model.onEvent(AdminEvent.ResetOrder)
        advanceUntilIdle()

        assertFalse(model.state.value.selection.isReordered(model.state.value.departments))
    }

    /** A move off either end is a keyboard repeat, not an error. */
    @Test
    fun `a move past the end does nothing`() {
        val order = listOf(Department("a", "A"), Department("b", "B"))

        assertEquals(order, order.moved("a", -1))
        assertEquals(order, order.moved("b", 1))
        assertEquals(order, order.moved("missing", 1))
        assertEquals(listOf("b", "a"), order.moved("a", 1).map { it.id })
    }

    // -- tools ---------------------------------------------------------------------------

    @Test
    fun `ticking tools is local until saved`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.ToolAvailability))
        advanceUntilIdle()
        model.onEvent(AdminEvent.ToolEnabledChanged("casting_tool", false))
        advanceUntilIdle()

        assertEquals(listOf("tools:false"), repository.calls)
        assertFalse(model.state.value.tools.single().enabled)

        model.onEvent(AdminEvent.SaveTools)
        advanceUntilIdle()

        assertEquals("setToolsEnabled:0", repository.calls[1])
    }

    /** The grouping page needs the always-on tools too; the availability page does not. */
    @Test
    fun `the grouping page asks for the full tool list`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.ToolGroups))
        advanceUntilIdle()

        assertTrue(repository.calls.contains("tools:true"))
    }

    /** Ungrouping is a real move, and the blank identifier must survive. */
    @Test
    fun `ungrouping a tool sends the empty group`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.ToolGroups))
        advanceUntilIdle()
        model.onEvent(AdminEvent.MoveTool("casting_tool", ""))
        advanceUntilIdle()

        assertTrue(repository.calls.contains("moveTool:casting_tool:"))
    }

    // -- forms with more than one field ----------------------------------------------------

    @Test
    fun `choosing a department clears the job title under it`() = runTest {
        val model = viewModel()

        model.onEvent(AdminEvent.Opened(AdminDestination.PreApproved))
        advanceUntilIdle()
        model.onEvent(AdminEvent.OpenPreApproval())
        model.onEvent(AdminEvent.FieldChanged(AdminField.Department, "d1"))
        model.onEvent(AdminEvent.FieldChanged(AdminField.JobTitle, "r1"))
        model.onEvent(AdminEvent.FieldChanged(AdminField.Department, "d2"))

        val form = model.state.value.form as? AdminForm.PreApproval
        assertNotNull(form)
        assertEquals("d2", form.departmentId)
        // Keeping it would pre-approve someone into a job their department does
        // not have.
        assertNull(form.jobTitleId)
    }

    @Test
    fun `a phone number without its country code is refused`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.PreApproved))
        advanceUntilIdle()
        model.onEvent(AdminEvent.OpenPreApproval())
        model.onEvent(AdminEvent.FieldChanged(AdminField.FirstName, "Margaret"))
        model.onEvent(AdminEvent.FieldChanged(AdminField.LastName, "Hamilton"))
        model.onEvent(AdminEvent.FieldChanged(AdminField.Department, "d1"))
        model.onEvent(AdminEvent.FieldChanged(AdminField.JobTitle, "r1"))
        model.onEvent(AdminEvent.FieldChanged(AdminField.Phone, "7700900000"))
        model.onEvent(AdminEvent.SubmitForm)
        advanceUntilIdle()

        assertFalse(repository.calls.any { it.startsWith("addPreApproved") })
        assertNotNull((model.state.value.form as? AdminForm.PreApproval)?.error)
    }

    @Test
    fun `a company email that is not an address is refused`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.CompanyDetails))
        advanceUntilIdle()
        model.onEvent(AdminEvent.OpenCompany)
        model.onEvent(
            AdminEvent.CompanyDraftChanged(CompanyDetails(name = "Zillit", email = "not-an-address")),
        )
        model.onEvent(AdminEvent.SubmitForm)
        advanceUntilIdle()

        assertFalse(repository.calls.any { it.startsWith("saveCompanyDetails") })
        assertNotNull((model.state.value.form as? AdminForm.Company)?.error)
    }

    /** An all-blank company block is legal — it clears the header. */
    @Test
    fun `an empty company block saves`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.CompanyDetails))
        advanceUntilIdle()
        model.onEvent(AdminEvent.OpenCompany)
        model.onEvent(AdminEvent.CompanyDraftChanged(CompanyDetails()))
        model.onEvent(AdminEvent.SubmitForm)
        advanceUntilIdle()

        assertTrue(repository.calls.contains("saveCompanyDetails:"))
    }

    // -- deletion -------------------------------------------------------------------------

    @Test
    fun `scheduling a deletion asks first and names the delay`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.DeleteProduction))
        advanceUntilIdle()
        model.onEvent(AdminEvent.Ask(AdminConfirmation.DeleteProduction(48, "Feature One")))
        advanceUntilIdle()

        assertTrue(repository.calls.none { it.startsWith("scheduleDeletion") })

        model.onEvent(AdminEvent.ConfirmAction)
        advanceUntilIdle()

        assertTrue(repository.calls.contains("scheduleDeletion:48"))
        assertTrue(model.state.value.deletion.isScheduled)
        assertEquals(48, model.state.value.deletion.hours)
    }

    /** Calling it off is the safe direction, so it does not ask. */
    @Test
    fun `calling off a deletion needs no confirmation`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.DeleteProduction))
        advanceUntilIdle()
        model.onEvent(AdminEvent.CancelDeletion)
        advanceUntilIdle()

        assertTrue(repository.calls.contains("cancelDeletion"))
    }
    /**
     * Every write on this surface rewrites the production's own rights —
     * granting administrator among them. The screen refuses a non-admin
     * before drawing a control (`AdminSettingsScreen` renders `NotAnAdmin()`
     * and returns), but the view model took whatever event reached it.
     */
    @Test
    fun `a non-admin cannot change the production`() = runTest {
        val repository = Recorder()
        val model = AdminViewModel(
            repository,
            productionName = { "Feature One" },
            isAdmin = { false },
        )

        model.onEvent(AdminEvent.CancelDeletion)

        assertTrue(
            repository.calls.isEmpty(),
            "a non-admin changed the project: ${repository.calls}",
        )
        assertNotNull(model.state.value.error)
    }

}
