package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.settings.admin.domain.AccessType
import com.zillit.desktop.feature.settings.admin.domain.AdminUnit
import com.zillit.desktop.feature.settings.admin.domain.CompanyDetails
import com.zillit.desktop.feature.settings.admin.domain.CrewMember
import com.zillit.desktop.feature.settings.admin.domain.CrewStatus
import com.zillit.desktop.feature.settings.admin.domain.Department
import com.zillit.desktop.feature.settings.admin.domain.JobTitle
import com.zillit.desktop.feature.settings.admin.domain.RightsSection
import com.zillit.desktop.feature.settings.admin.domain.RightsWrite
import com.zillit.desktop.feature.settings.admin.domain.ToolRights
import com.zillit.desktop.feature.settings.admin.domain.UnitKind
import com.zillit.desktop.feature.settings.admin.domain.rightsCascade
import com.zillit.desktop.feature.settings.admin.ui.AdminConfirmation
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminEvent
import com.zillit.desktop.feature.settings.admin.ui.AdminField
import com.zillit.desktop.feature.settings.admin.ui.AdminForm
import com.zillit.desktop.feature.settings.admin.ui.AdminViewModel
import com.zillit.desktop.feature.settings.admin.ui.NameKind
import com.zillit.desktop.feature.settings.admin.ui.moved
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

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

    private fun viewModel(repository: Recorder = Recorder()) =
        AdminViewModel(repository, productionName = { "Feature One" }, selfUserId = { "me" })

    /** What User Management reads on arrival: the Change Profile pickers, then the people. */
    private val crewReads = listOf("departments", "units:Shooting", "crew")

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
        assertEquals(crewReads, repository.calls)
        assertTrue(model.state.value.confirming is AdminConfirmation.GrantAdmin)

        model.onEvent(AdminEvent.ConfirmAction)
        advanceUntilIdle()
        assertTrue(repository.calls.contains("setAdminAccess:u1:true"))

        model.onEvent(AdminEvent.AdminAccessChanged("u2", false))
        advanceUntilIdle()
        assertTrue(repository.calls.contains("setAdminAccess:u2:false"))
        assertNull(model.state.value.confirming)
    }

    /** The web's Active switch writes at once in both directions; each is the other's undo. */
    @Test
    fun `switching someone off the project and back writes at once`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.Crew))
        advanceUntilIdle()

        model.onEvent(AdminEvent.CrewActiveChanged("u1", false))
        advanceUntilIdle()
        assertNull(model.state.value.confirming)
        assertTrue(repository.calls.contains("setCrewStatus:u1:removed"))

        model.onEvent(AdminEvent.CrewActiveChanged("u1", true))
        advanceUntilIdle()
        assertTrue(repository.calls.contains("setCrewStatus:u1:accepted"))
    }

    @Test
    fun `dismissing a confirmation does nothing at all`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.Crew))
        advanceUntilIdle()
        model.onEvent(AdminEvent.Ask(AdminConfirmation.GrantAdmin("u1", "Ada")))
        model.onEvent(AdminEvent.DismissConfirmation)
        advanceUntilIdle()

        assertEquals(crewReads, repository.calls)
        assertNull(model.state.value.confirming)
    }

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
     * The page opened offering the three delays however the production stood,
     * so an admin who had already scheduled a deletion — before this app was
     * started, or from another device — saw no sign of it and nothing to call
     * it off with. It read exactly as though the deletion had never happened.
     */
    @Test
    fun `the page opens knowing a deletion is already counting down`() = runTest {
        val model = AdminViewModel(
            Recorder(),
            productionName = { "Feature One" },
            markedForDeletion = { true },
        )

        model.onEvent(AdminEvent.Opened(AdminDestination.DeleteProduction))
        advanceUntilIdle()

        assertTrue(model.state.value.deletion.isScheduled)
    }

    /** Read on every visit: the answer can change while the admin is elsewhere. */
    @Test
    fun `revisiting the page reads the schedule again`() = runTest {
        var scheduled = false
        val model = AdminViewModel(
            Recorder(),
            productionName = { "Feature One" },
            markedForDeletion = { scheduled },
        )

        model.onEvent(AdminEvent.Opened(AdminDestination.DeleteProduction))
        advanceUntilIdle()
        assertFalse(model.state.value.deletion.isScheduled)

        scheduled = true
        model.onEvent(AdminEvent.Opened(AdminDestination.Crew))
        advanceUntilIdle()
        model.onEvent(AdminEvent.Opened(AdminDestination.DeleteProduction))
        advanceUntilIdle()

        assertTrue(model.state.value.deletion.isScheduled)
    }

    /**
     * The schedule this screen just set outlives the reload that follows it.
     *
     * `mutate` reloads the page on success, and the production snapshot behind
     * `markedForDeletion` is read when the production is opened — so it still
     * says "no deletion" for a moment after one is scheduled. Re-seeding from
     * it there would blank the notice the admin just earned.
     */
    @Test
    fun `scheduling survives the reload that follows it`() = runTest {
        val model = AdminViewModel(
            Recorder(),
            productionName = { "Feature One" },
            markedForDeletion = { false },
        )

        model.onEvent(AdminEvent.Opened(AdminDestination.DeleteProduction))
        advanceUntilIdle()
        model.onEvent(AdminEvent.Ask(AdminConfirmation.DeleteProduction(24, "Feature One")))
        model.onEvent(AdminEvent.ConfirmAction)
        advanceUntilIdle()

        assertTrue(model.state.value.deletion.isScheduled)
        assertEquals(24, model.state.value.deletion.hours)
    }

    /** The notice and its button used to survive the call that called it off. */
    @Test
    fun `calling off a deletion clears the notice`() = runTest {
        val model = AdminViewModel(
            Recorder(),
            productionName = { "Feature One" },
            markedForDeletion = { true },
        )

        model.onEvent(AdminEvent.Opened(AdminDestination.DeleteProduction))
        advanceUntilIdle()
        assertTrue(model.state.value.deletion.isScheduled)

        model.onEvent(AdminEvent.CancelDeletion)
        advanceUntilIdle()

        assertFalse(model.state.value.deletion.isScheduled)
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

    // -- user management --------------------------------------------------------

    @Test
    fun `user management lists neither the reader nor anyone who left or is pending`() = runTest {
        val repository = Recorder().apply {
            crewAnswer = ZillitResult.Success(
                listOf(
                    CrewMember("me", "The Admin"),
                    CrewMember("u1", "Ada Lovelace"),
                    CrewMember("u2", "Gone", status = CrewStatus.Left),
                    CrewMember("u3", "Waiting", status = CrewStatus.Pending),
                    CrewMember("u4", "Turned away", status = CrewStatus.Rejected),
                    CrewMember("u5", "Switched off", status = CrewStatus.Removed),
                ),
            )
        }
        val model = viewModel(repository)

        model.onEvent(AdminEvent.Opened(AdminDestination.Crew))
        advanceUntilIdle()

        assertEquals(listOf("u1", "u5"), model.state.value.crewMatching.map { it.userId })
    }

    @Test
    fun `a switched off person refuses everything but Active, with the web's warning`() = runTest {
        val repository = Recorder().apply {
            crewAnswer = ZillitResult.Success(listOf(CrewMember("u1", "Ada", status = CrewStatus.Removed)))
        }
        val model = viewModel(repository)
        model.onEvent(AdminEvent.Opened(AdminDestination.Crew))
        advanceUntilIdle()

        model.onEvent(AdminEvent.AdminAccessChanged("u1", true))
        model.onEvent(AdminEvent.OpenEditCrew("u1", withUnit = true))
        model.onEvent(AdminEvent.OpenPostingRights("u1"))
        advanceUntilIdle()

        assertEquals(crewReads, repository.calls)
        assertNull(model.state.value.confirming)
        assertNull(model.state.value.form)
        assertNotNull(model.state.value.error)
    }

    @Test
    fun `change profile opens on where the person is and sends ids`() = runTest {
        val repository = Recorder().apply {
            crewAnswer = ZillitResult.Success(
                listOf(CrewMember("u1", "Ada", department = "Art", designation = "Standby Art", joinUnitName = "Main")),
            )
            unitsAnswer = listOf(AdminUnit("unit-1", "Main", UnitKind.Shooting))
        }
        val model = viewModel(repository)
        model.onEvent(AdminEvent.Opened(AdminDestination.Crew))
        advanceUntilIdle()

        model.onEvent(AdminEvent.OpenEditCrew("u1", withUnit = true))
        val form = model.state.value.form as AdminForm.EditCrew
        assertEquals("d1", form.departmentId)
        assertEquals("r1", form.designationId)
        assertEquals("unit-1", form.unitId)

        model.onEvent(AdminEvent.SubmitForm)
        advanceUntilIdle()
        assertTrue(repository.calls.contains("updateCrewProfile:u1:d1:r1:unit-1:false"), "${repository.calls}")
        assertNull(model.state.value.form)
    }

    @Test
    fun `change profile refuses a missing designation without a call`() = runTest {
        val repository = Recorder()
        val model = viewModel(repository)
        model.onEvent(AdminEvent.Opened(AdminDestination.Crew))
        advanceUntilIdle()

        model.onEvent(AdminEvent.OpenEditCrew("u1", withUnit = false))
        val form = model.state.value.form as AdminForm.EditCrew
        model.onEvent(AdminEvent.EditCrewChanged(form.copy(departmentId = "d2")))
        model.onEvent(AdminEvent.SubmitForm)
        advanceUntilIdle()

        assertFalse(repository.calls.any { it.startsWith("updateCrewProfile") })
        assertNotNull((model.state.value.form as AdminForm.EditCrew).error)
    }

    @Test
    fun `switching viewing off takes posting and downloading with it, then rereads`() = runTest {
        val repository = Recorder().apply {
            rightsAnswer = listOf(
                ToolRights(
                    unitId = "t1", identifier = "drive_tool", name = "Drive", section = RightsSection.Home,
                    canView = true, canPost = true, canDownload = true,
                    viewUpdatable = true, postUpdatable = true, downloadUpdatable = true,
                ),
            )
        }
        val model = viewModel(repository)
        model.onEvent(AdminEvent.Opened(AdminDestination.Crew))
        advanceUntilIdle()
        model.onEvent(AdminEvent.OpenPostingRights("u1"))
        advanceUntilIdle()
        repository.calls.clear()

        model.onEvent(AdminEvent.RightToggled("t1", AccessType.View, false))
        advanceUntilIdle()

        assertEquals(
            listOf(
                "writeRight:u1:home:t1:view:false",
                "writeRight:u1:home:t1:download:false",
                "writeRight:u1:home:t1:post:false",
                "rights:u1",
            ),
            repository.calls,
        )
    }

    @Test
    fun `main budget view on drags the department budget along`() {
        val main = ToolRights(
            "m", ToolRights.MAIN_BUDGET, "Main", RightsSection.Tools, viewUpdatable = true,
        )
        val department = ToolRights("d", ToolRights.DEPARTMENT_BUDGET, "Dept", RightsSection.Tools)

        assertEquals(
            listOf(RightsWrite("m", AccessType.View, true), RightsWrite("d", AccessType.View, true)),
            rightsCascade(main, AccessType.View, true, listOf(main, department)),
        )
        assertEquals(
            listOf(RightsWrite("m", AccessType.View, false)),
            rightsCascade(main, AccessType.View, false, listOf(main, department)),
        )
    }

    @Test
    fun `allow chat opens on the saved list and sends the whole selection`() = runTest {
        val repository = Recorder().apply {
            crewAnswer = ZillitResult.Success(
                listOf(
                    CrewMember("u1", "Ada", keepNamePrivate = true),
                    CrewMember("u2", "Grace"),
                    CrewMember("u3", "Katherine"),
                ),
            )
        }
        val model = viewModel(repository)
        model.onEvent(AdminEvent.Opened(AdminDestination.Crew))
        advanceUntilIdle()

        model.onEvent(AdminEvent.OpenAllowChat("u1"))
        advanceUntilIdle()
        val form = model.state.value.form as AdminForm.AllowChat
        assertEquals(setOf("u2"), form.selected)

        model.onEvent(AdminEvent.AllowChatChanged(form.copy(selected = form.selected + "u3")))
        model.onEvent(AdminEvent.SubmitForm)
        advanceUntilIdle()
        assertTrue(repository.calls.contains("setChatAllowList:u1:u2,u3"))
    }
}
