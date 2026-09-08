package com.zillit.desktop.feature.auth

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.units.ProductionUnit
import com.zillit.desktop.core.units.UnitRepository
import com.zillit.desktop.feature.auth.domain.Department
import com.zillit.desktop.feature.auth.domain.Designation
import com.zillit.desktop.feature.auth.domain.ChosenPhoto
import com.zillit.desktop.feature.auth.domain.CodeLookup
import com.zillit.desktop.feature.auth.domain.JoinDraft
import com.zillit.desktop.feature.auth.domain.JoinFieldError
import com.zillit.desktop.feature.auth.domain.JoinPhoto
import com.zillit.desktop.feature.auth.domain.JoinStatus
import com.zillit.desktop.feature.auth.domain.NewProductionDraft
import com.zillit.desktop.feature.auth.domain.ProductionType
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.domain.ProjectRepository
import com.zillit.desktop.feature.auth.domain.validate
import com.zillit.desktop.feature.auth.ui.JoinEvent
import com.zillit.desktop.feature.auth.ui.JoinProductionViewModel
import com.zillit.desktop.feature.auth.ui.JoinStep
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.zillit.desktop.feature.auth.domain.Unit as DomainUnit

/**
 * Asking to join a production.
 *
 * The rules here are the other clients' rules, not invented ones: iOS's
 * `validateProjectType` is where the three-letter names and the personal-project
 * exemption come from, and both it and Android send the same body.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinProductionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val camera = Department(
        id = "d1",
        name = "Camera",
        designations = listOf(Designation("r1", "Focus Puller")),
    )
    private val costume = Department(
        id = "d2",
        name = "Costume",
        designations = listOf(Designation("r2", "Costume Supervisor")),
    )
    private val mainUnit = ProductionUnit("u1", "Main Unit")

    private fun viewModel(projects: FakeProjects = FakeProjects()) =
        JoinProductionViewModel(projects, FakeUnits())

    /** A stored photo, and a picker that always finds one. */
    private val storedPhoto = JoinPhoto(
        media = "profile-picture/abc/selfie.jpg",
        thumbnail = "profile-picture/abc/selfie.jpg",
        bucket = "zillit-dev-uploads",
        region = "ap-south-1",
    )

    private fun withPhoto(
        projects: FakeProjects = FakeProjects(),
        chosen: ChosenPhoto? = ChosenPhoto("selfie.jpg", "image/jpeg", ByteArray(8)),
        stores: Boolean = true,
    ) = JoinProductionViewModel(
        projects,
        FakeUnits(),
        photoStore = {
            if (stores) {
                ZillitResult.Success(storedPhoto)
            } else {
                ZillitResult.Failure(ZillitError.Storage(technical = "no bucket", userMessage = "No storage."))
            }
        },
        choosePhoto = { chosen },
    )

    /** Drives [model] to the details step. */
    private fun JoinProductionViewModel.findProduction() {
        onEvent(JoinEvent.CodeChanged("ABC123"))
        onEvent(JoinEvent.FindProject)
        dispatcher.scheduler.advanceUntilIdle()
    }

    /** Drives the flow to the details step, as finding a code does. */
    private fun atDetails(projects: FakeProjects = FakeProjects()): JoinProductionViewModel {
        val model = viewModel(projects)
        model.onEvent(JoinEvent.CodeChanged("ABC123"))
        model.onEvent(JoinEvent.FindProject)
        dispatcher.scheduler.advanceUntilIdle()
        return model
    }

    // -- the profile picture -----------------------------------------------

    @Test
    fun `choosing a photo stores it and puts the keys on the draft`() = runTest(dispatcher) {
        val model = withPhoto()
        model.findProduction()

        model.onEvent(JoinEvent.ChoosePhoto)
        advanceUntilIdle()

        assertEquals(storedPhoto, model.state.value.photo)
        assertEquals(false, model.state.value.isStoringPhoto)
        assertNull(model.state.value.photoError)
    }

    @Test
    fun `the stored keys reach the request`() = runTest(dispatcher) {
        val repo = FakeProjects()
        val model = withPhoto(repo)
        model.findProduction()
        model.onEvent(JoinEvent.ChoosePhoto)
        advanceUntilIdle()

        model.onEvent(JoinEvent.DraftChanged(complete()))
        model.onEvent(JoinEvent.Submit)
        advanceUntilIdle()

        assertEquals(storedPhoto, repo.joined.single().second.photo)
    }

    @Test
    fun `a join without a photo carries none`() = runTest(dispatcher) {
        val repo = FakeProjects()
        val model = viewModel(repo)
        model.findProduction()

        model.onEvent(JoinEvent.DraftChanged(complete()))
        model.onEvent(JoinEvent.Submit)
        advanceUntilIdle()

        assertNull(repo.joined.single().second.photo)
    }

    @Test
    fun `a picture that will not store never blocks the join`() = runTest(dispatcher) {
        // A production would rather have the crew member than the photograph.
        val model = withPhoto(stores = false)
        model.findProduction()

        model.onEvent(JoinEvent.ChoosePhoto)
        advanceUntilIdle()

        assertNull(model.state.value.photo)
        assertNotNull(model.state.value.photoError)
        assertEquals(false, model.state.value.isStoringPhoto)
    }

    @Test
    fun `cancelling the picker changes nothing`() = runTest(dispatcher) {
        val model = withPhoto(chosen = null)
        model.findProduction()

        model.onEvent(JoinEvent.ChoosePhoto)
        advanceUntilIdle()

        assertNull(model.state.value.photo)
        assertNull(model.state.value.photoError)
        assertEquals(false, model.state.value.isStoringPhoto)
    }

    @Test
    fun `a photo can be taken off again`() = runTest(dispatcher) {
        val model = withPhoto()
        model.findProduction()
        model.onEvent(JoinEvent.ChoosePhoto)
        advanceUntilIdle()

        model.onEvent(JoinEvent.RemovePhoto)

        assertNull(model.state.value.photo)
    }

    @Test
    fun `a host with no storage does not offer a photo at all`() = runTest(dispatcher) {
        // Better than offering a control that silently cannot save.
        assertEquals(false, viewModel().state.value.canChoosePhoto)
        assertTrue(withPhoto().state.value.canChoosePhoto)
    }

    // -- finding the production --------------------------------------------

    @Test
    fun `a valid code moves on to the details`() = runTest(dispatcher) {
        val model = atDetails()

        assertEquals(JoinStep.Details, model.state.value.step)
        assertEquals("Feature Film", model.state.value.project?.name)
    }

    @Test
    fun `the dropdowns are ready when the step appears`() = runTest(dispatcher) {
        // Loaded with the lookup, not when the step renders — otherwise the
        // form arrives with three empty dropdowns and fills itself in later.
        val model = atDetails()

        assertEquals(listOf(camera, costume), model.state.value.departments)
        assertEquals(listOf(mainUnit), model.state.value.units)
    }

    @Test
    fun `a code issued to one person skips the form entirely`() = runTest(dispatcher) {
        // They were pre-approved: the server has already put them on the
        // production, and asking for a department would open a second request
        // against their own membership.
        val model = viewModel(FakeProjects(preApproved = true))
        model.onEvent(JoinEvent.CodeChanged("PRE-APPROVED"))
        model.onEvent(JoinEvent.FindProject)
        advanceUntilIdle()

        assertEquals(JoinStep.Submitted, model.state.value.step)
        assertEquals(JoinStatus.Approved, model.state.value.outcome)
        assertNull(model.state.value.project, "there is no form to name a project on")
        assertEquals(false, model.state.value.isBusy)
    }

    @Test
    fun `an unknown code stays on the code step and says so`() = runTest(dispatcher) {
        val model = atDetails(FakeProjects(findFails = true))

        assertEquals(JoinStep.Code, model.state.value.step)
        assertNotNull(model.state.value.error)
    }

    // -- the details -------------------------------------------------------

    @Test
    fun `changing department clears the role`() = runTest(dispatcher) {
        // The old role belongs to another department; a costume role filed
        // under camera is not noticed until the crew list is printed.
        val model = atDetails()
        model.onEvent(JoinEvent.DraftChanged(JoinDraft(departmentId = "d1", designationId = "r1")))
        dispatcher.scheduler.advanceUntilIdle()

        model.onEvent(JoinEvent.DraftChanged(model.state.value.draft.copy(departmentId = "d2")))
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(model.state.value.draft.designationId)
    }

    @Test
    fun `only the chosen department's roles are offered`() = runTest(dispatcher) {
        val model = atDetails()

        model.onEvent(JoinEvent.DraftChanged(JoinDraft(departmentId = "d2")))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(Designation("r2", "Costume Supervisor")), model.state.value.designations)
    }

    // -- what the server is told -------------------------------------------

    @Test
    fun `a complete request is sent`() = runTest(dispatcher) {
        val projects = FakeProjects()
        val model = atDetails(projects)
        model.onEvent(JoinEvent.DraftChanged(complete()))
        dispatcher.scheduler.advanceUntilIdle()

        model.onEvent(JoinEvent.Submit)
        advanceUntilIdle()

        assertEquals(listOf("p1" to complete()), projects.joined)
    }

    @Test
    fun `the confirmation says the request is waiting`() = runTest(dispatcher) {
        // A request pending a coordinator looks identical to one that failed
        // unless the screen says otherwise.
        val model = atDetails()
        model.onEvent(JoinEvent.DraftChanged(complete()))
        dispatcher.scheduler.advanceUntilIdle()

        model.onEvent(JoinEvent.Submit)
        advanceUntilIdle()

        assertEquals(JoinStep.Submitted, model.state.value.step)
        assertEquals(JoinStatus.Pending, model.state.value.outcome)
    }

    @Test
    fun `an incomplete request is not sent`() = runTest(dispatcher) {
        val projects = FakeProjects()
        val model = atDetails(projects)
        model.onEvent(JoinEvent.DraftChanged(JoinDraft(firstName = "Jo", lastName = "Bloggs")))
        dispatcher.scheduler.advanceUntilIdle()

        model.onEvent(JoinEvent.Submit)
        advanceUntilIdle()

        assertTrue(projects.joined.isEmpty())
        assertTrue(model.state.value.errors.isNotEmpty())
    }

    // -- the rules, straight from the other clients ------------------------

    private fun complete() = JoinDraft(
        firstName = "Alex",
        lastName = "Bloggs",
        departmentId = "d1",
        designationId = "r1",
        unitId = "u1",
    )

    @Test
    fun `a complete draft passes`() {
        assertTrue(complete().validate(isPersonalProduction = false).isEmpty())
    }

    @Test
    fun `names shorter than three letters are refused`() {
        // `FirstNameMinLimit` / `LastNameMinLimit` in the iOS client. These end
        // up on call sheets, where "J" is a puzzle rather than a name.
        val short = complete().copy(firstName = "Jo", lastName = "Li")

        val errors = short.validate(isPersonalProduction = false)
        assertTrue(JoinFieldError.FirstNameTooShort in errors)
        assertTrue(JoinFieldError.LastNameTooShort in errors)
    }

    @Test
    fun `crew fields are required on a real production`() {
        val bare = JoinDraft(firstName = "Alex", lastName = "Bloggs")

        val errors = bare.validate(isPersonalProduction = false)
        assertEquals(
            setOf(
                JoinFieldError.DepartmentMissing,
                JoinFieldError.DesignationMissing,
                JoinFieldError.UnitMissing,
            ),
            errors,
        )
    }

    @Test
    fun `a personal production asks only for a name`() {
        // It has no crew, so department, role and unit are questions with no
        // answer. Both other clients skip them for it.
        val bare = JoinDraft(firstName = "Alex", lastName = "Bloggs")

        assertTrue(bare.validate(isPersonalProduction = true).isEmpty())
    }

    @Test
    fun `whitespace is not a name`() {
        val padded = complete().copy(firstName = "  A  ")

        assertTrue(JoinFieldError.FirstNameTooShort in padded.validate(isPersonalProduction = false))
    }

    // -- fakes -------------------------------------------------------------

    private inner class FakeProjects(
        private val findFails: Boolean = false,
        /** Answers as a code issued to one person, who is already on the production. */
        private val preApproved: Boolean = false,
    ) : ProjectRepository {
        val joined = mutableListOf<Pair<String, JoinDraft>>()

        override suspend fun findByCode(code: String): ZillitResult<CodeLookup> = when {
            findFails ->
                ZillitResult.Failure(ZillitError.Validation("No project found for that code."))

            preApproved ->
                ZillitResult.Success(CodeLookup.AlreadyOn(projectId = "p1", userId = "u1"))

            else -> ZillitResult.Success(
                CodeLookup.NeedsDetails(
                    Project(id = "p1", name = "Feature Film", code = code, type = null, region = null),
                ),
            )
        }

        override suspend fun departments(projectId: String) =
            ZillitResult.Success(listOf(camera, costume))

        override suspend fun requestJoin(
            projectId: String,
            draft: JoinDraft,
        ): ZillitResult<JoinStatus> {
            joined += projectId to draft
            return ZillitResult.Success(JoinStatus.Pending)
        }

        override suspend fun create(
            draft: NewProductionDraft,
            selectedType: ProductionType?,
            confirmCode: String,
        ) = ZillitResult.Success(
            Project(id = "new", name = draft.productionName, code = "N", type = null, region = null),
        )

        override suspend fun leaveProject() = ZillitResult.Success(Unit)
        override suspend fun setFavourite(projectId: String, favourite: Boolean) =
            ZillitResult.Success(Unit)
        override suspend fun listProjects() = ZillitResult.Success(emptyList<Project>())
        override suspend fun joinStatus(projectId: String) = ZillitResult.Success(JoinStatus.Pending)
        override suspend fun listUnits(projectId: String) =
            ZillitResult.Success(emptyList<DomainUnit>())
        override suspend fun selectProject(project: Project, unit: DomainUnit?) =
            ZillitResult.Success(Unit)
    }

    private inner class FakeUnits : UnitRepository {
        override suspend fun joinUnits(projectId: String?) = ZillitResult.Success(listOf(mainUnit))
        override suspend fun setJoinUnit(unitId: String) = ZillitResult.Success(Unit)
    }
}
