package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.units.ProductionUnit
import com.zillit.desktop.feature.settings.approvals.ApprovalQueue
import com.zillit.desktop.feature.settings.approvals.ApprovalsEffect
import com.zillit.desktop.feature.settings.approvals.ApprovalsEvent
import com.zillit.desktop.feature.settings.approvals.ApprovalsRepository
import com.zillit.desktop.feature.settings.approvals.ApprovalsViewModel
import com.zillit.desktop.feature.settings.approvals.CrewDepartment
import com.zillit.desktop.feature.settings.approvals.CrewPresets
import com.zillit.desktop.feature.settings.approvals.CrewRole
import com.zillit.desktop.feature.settings.approvals.KnownCrewMember
import com.zillit.desktop.feature.settings.approvals.PendingApproval
import com.zillit.desktop.feature.settings.approvals.approvalBody
import com.zillit.desktop.feature.settings.approvals.waitedFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two approval queues.
 *
 * These are the calls that let a stranger onto a production. What matters:
 * declining cannot happen by accident, a decision that failed leaves the person
 * in the queue, and a decline never carries the placement it is refusing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun request(id: String, name: String = "Aisha Khan") = PendingApproval(
        id = id,
        userId = "user-$id",
        fullName = name,
        firstName = name.substringBefore(' '),
        lastName = name.substringAfter(' ', ""),
        deviceId = "device-$id",
        projectId = "project-1",
        departmentId = "dept-1",
        departmentName = "Camera",
        designationId = "role-1",
        designationName = "Focus Puller",
        unitId = "unit-1",
        unitName = "Main unit",
        requestedAtMillis = 1_000,
    )

    /** Records what it was asked, and answers what the test told it to. */
    private class FakeRepository(
        var listing: ZillitResult<List<PendingApproval>> = ZillitResult.Success(emptyList()),
        var decision: ZillitResult<Unit> = ZillitResult.Success(Unit),
    ) : ApprovalsRepository {
        var listReads = 0
        val decisions = mutableListOf<Triple<ApprovalQueue, String, Boolean>>()

        /** The requests as they went out — the form edits them before this. */
        val decided = mutableListOf<PendingApproval>()

        override suspend fun pending(queue: ApprovalQueue) = listing.also { listReads++ }

        override suspend fun decide(
            queue: ApprovalQueue,
            request: PendingApproval,
            approved: Boolean,
        ) = decision.also {
            decisions += Triple(queue, request.id, approved)
            decided += request
        }
    }

    private fun viewModel(
        repository: ApprovalsRepository,
        known: (String) -> KnownCrewMember? = { null },
    ) = ApprovalsViewModel(repository, knownCrew = known, nowMillis = { NOW })

    // -- deciding several at once --------------------------------------------

    @Test
    fun `select all ticks every visible request`() = runTest {
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"), request("b"))))
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.SelectAll(ApprovalQueue.NewCrew, on = true))
        assertTrue(approvals.state.value.crew.allVisibleSelected)

        approvals.onEvent(ApprovalsEvent.SelectAll(ApprovalQueue.NewCrew, on = false))
        assertTrue(approvals.state.value.crew.selected.isEmpty())
    }

    @Test
    fun `approving the selection decides each ticked request, and only those`() = runTest {
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"), request("b"), request("c"))))
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.ToggleSelected(ApprovalQueue.NewCrew, "a"))
        approvals.onEvent(ApprovalsEvent.ToggleSelected(ApprovalQueue.NewCrew, "c"))
        approvals.onEvent(ApprovalsEvent.ApproveSelected(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        assertEquals(
            listOf(Triple(ApprovalQueue.NewCrew, "a", true), Triple(ApprovalQueue.NewCrew, "c", true)),
            repository.decisions,
        )
        val crew = approvals.state.value.crew
        assertEquals(listOf("b"), crew.items.map { it.id })
        assertTrue(crew.selected.isEmpty())
        assertFalse(crew.isDecidingSelected)
    }

    @Test
    fun `declining the selection asks first`() = runTest {
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"), request("b"))))
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.SelectAll(ApprovalQueue.NewCrew, on = true))
        approvals.onEvent(ApprovalsEvent.AskDeclineSelected(ApprovalQueue.NewCrew))
        advanceUntilIdle()
        assertTrue(approvals.state.value.crew.confirmingSelected)
        assertTrue(repository.decisions.isEmpty())

        approvals.onEvent(ApprovalsEvent.ConfirmDeclineSelected(ApprovalQueue.NewCrew))
        advanceUntilIdle()
        assertEquals(listOf(false, false), repository.decisions.map { it.third })
        assertTrue(approvals.state.value.crew.items.isEmpty())
    }

    @Test
    fun `a failed request in a selection stays ticked for another try`() = runTest {
        val repository = FakeRepository(
            ZillitResult.Success(listOf(request("a"))),
            decision = ZillitResult.Failure(ZillitError.Validation("refused")),
        )
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.SelectAll(ApprovalQueue.NewCrew, on = true))
        approvals.onEvent(ApprovalsEvent.ApproveSelected(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        val crew = approvals.state.value.crew
        assertEquals(setOf("a"), crew.selected)
        assertEquals(listOf("a"), crew.items.map { it.id })
    }

    // -- reading the queue -------------------------------------------------

    @Test
    fun `every visit reads the queue again`() = runTest {
        // A request can arrive while the admin is on another page; coming back
        // must show it, not the list from the first visit. (What the old
        // read-once rule protected — a decision part-way through — is covered
        // in ApprovalsSyncTest.)
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"))))
        val approvals = viewModel(repository)

        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()
        repository.listing = ZillitResult.Success(listOf(request("a"), request("b", "Sam Reed")))
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        assertEquals(2, repository.listReads)
        assertEquals(2, approvals.state.value.crew.items.size)
    }

    @Test
    fun `refreshing reads it again`() = runTest {
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"))))
        val approvals = viewModel(repository)

        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()
        approvals.onEvent(ApprovalsEvent.Refresh(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        assertEquals(2, repository.listReads)
    }

    @Test
    fun `the two queues do not share a list`() = runTest {
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"))))
        val approvals = viewModel(repository)

        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        assertEquals(1, approvals.state.value.crew.items.size)
        assertTrue(approvals.state.value.profiles.items.isEmpty())
        assertFalse(approvals.state.value.profiles.hasLoaded)
    }

    @Test
    fun `a failed read keeps what was already listed`() = runTest {
        // A dropped request is not evidence that the queue emptied, and
        // blanking it would tell an admin there is nothing left to do.
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"))))
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        repository.listing = ZillitResult.Failure(ZillitError.NoConnection("offline"))
        approvals.onEvent(ApprovalsEvent.Refresh(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        val crew = approvals.state.value.crew
        assertEquals(1, crew.items.size)
        assertTrue(crew.error != null)
    }

    // -- deciding ----------------------------------------------------------

    @Test
    fun `approving removes the row and reports it`() = runTest {
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"), request("b", "Sam Reed"))))
        val approvals = viewModel(repository)
        val effects = mutableListOf<ApprovalsEffect>()
        val job = CoroutineScope(dispatcher).launch { approvals.effects.collect(effects::add) }

        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()
        approvals.onEvent(ApprovalsEvent.Approve(ApprovalQueue.NewCrew, "a"))
        advanceUntilIdle()

        assertEquals(listOf(Triple(ApprovalQueue.NewCrew, "a", true)), repository.decisions)
        assertEquals(listOf("b"), approvals.state.value.crew.items.map { it.id })
        assertEquals("Aisha Khan was approved.", approvals.state.value.crew.outcome)
        assertTrue(effects.contains(ApprovalsEffect.Decided(ApprovalQueue.NewCrew, true)))
        job.cancel()
    }

    @Test
    fun `declining asks first`() = runTest {
        // The person has to start the whole join again if this was a slip.
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"))))
        val approvals = viewModel(repository)

        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()
        approvals.onEvent(ApprovalsEvent.AskDecline(ApprovalQueue.NewCrew, "a"))
        advanceUntilIdle()

        assertEquals("a", approvals.state.value.crew.confirming?.id)
        assertTrue(repository.decisions.isEmpty())
    }

    @Test
    fun `cancelling declines nobody`() = runTest {
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"))))
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.AskDecline(ApprovalQueue.NewCrew, "a"))
        approvals.onEvent(ApprovalsEvent.DismissDecline(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        assertNull(approvals.state.value.crew.confirming)
        assertTrue(repository.decisions.isEmpty())
        assertEquals(1, approvals.state.value.crew.items.size)
    }

    @Test
    fun `confirming declines`() = runTest {
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"))))
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.AskDecline(ApprovalQueue.NewCrew, "a"))
        approvals.onEvent(ApprovalsEvent.ConfirmDecline(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        assertEquals(listOf(Triple(ApprovalQueue.NewCrew, "a", false)), repository.decisions)
        assertTrue(approvals.state.value.crew.items.isEmpty())
    }

    @Test
    fun `a decision that failed leaves the person in the queue`() = runTest {
        // The row has to stay so the decision can be made again rather than
        // being lost with the error message.
        val repository = FakeRepository(
            listing = ZillitResult.Success(listOf(request("a"))),
            decision = ZillitResult.Failure(ZillitError.NoConnection("offline")),
        )
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.Approve(ApprovalQueue.NewCrew, "a"))
        advanceUntilIdle()

        val crew = approvals.state.value.crew
        assertEquals(1, crew.items.size)
        assertTrue(crew.deciding.isEmpty())
        assertTrue(crew.error.orEmpty().startsWith("Aisha Khan:"))
    }

    @Test
    fun `the same person cannot be decided twice at once`() = runTest {
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"))))
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.Approve(ApprovalQueue.NewCrew, "a"))
        approvals.onEvent(ApprovalsEvent.Approve(ApprovalQueue.NewCrew, "a"))
        advanceUntilIdle()

        assertEquals(1, repository.decisions.size)
    }

    // -- searching ---------------------------------------------------------

    @Test
    fun `searching narrows the queue by name and by role`() = runTest {
        val repository = FakeRepository(
            ZillitResult.Success(listOf(request("a"), request("b", "Sam Reed"))),
        )
        val approvals = viewModel(repository)
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.SearchChanged(ApprovalQueue.NewCrew, "sam"))
        assertEquals(listOf("b"), approvals.state.value.crew.visible.map { it.id })

        approvals.onEvent(ApprovalsEvent.SearchChanged(ApprovalQueue.NewCrew, "focus"))
        assertEquals(2, approvals.state.value.crew.visible.size)
    }

    // -- what goes on the wire ---------------------------------------------

    @Test
    fun `declining never carries the placement it is refusing`() {
        val body = approvalBody(ApprovalQueue.NewCrew, request("a"), approved = false, createsMailbox = true)

        assertEquals("no", body["approved"]?.jsonPrimitive?.content)
        assertEquals("user-a", body["user_id"]?.jsonPrimitive?.content)
        assertNull(body["department_id"])
        assertNull(body["designation_id"])
        assertNull(body["join_unit_id"])
        assertNull(body["create_email_box"])
    }

    @Test
    fun `approving someone new sends the placement back`() {
        // The server seats them from these — an approval that dropped the role
        // would let someone in with none.
        val body = approvalBody(ApprovalQueue.NewCrew, request("a"), approved = true, createsMailbox = true)

        assertEquals("yes", body["approved"]?.jsonPrimitive?.content)
        assertEquals("dept-1", body["department_id"]?.jsonPrimitive?.content)
        assertEquals("role-1", body["designation_id"]?.jsonPrimitive?.content)
        assertEquals("unit-1", body["join_unit_id"]?.jsonPrimitive?.content)
        assertEquals("device-a", body["device_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a personal production creates no mailbox`() {
        val body = approvalBody(ApprovalQueue.NewCrew, request("a"), approved = true, createsMailbox = false)

        assertEquals("false", body["create_email_box"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a profile change is decided against the request, not the person`() {
        // One person can have several outstanding, so the request id is what
        // the decision addresses.
        val body = approvalBody(ApprovalQueue.ProfileChanges, request("req-9"), approved = true, createsMailbox = true)

        assertEquals("req-9", body["request_id"]?.jsonPrimitive?.content)
        assertNull(body["user_id"])
        assertEquals("Aisha Khan", body["full_name"]?.jsonPrimitive?.content)
    }

    // -- the review form ---------------------------------------------------

    private val presets = CrewPresets(
        departments = listOf(
            CrewDepartment("dept-1", "Camera", listOf(CrewRole("role-1", "Focus Puller"))),
            CrewDepartment("dept-2", "Lighting", listOf(CrewRole("role-2", "Gaffer"))),
            CrewDepartment("dept-3", "Catering"),
        ),
        units = listOf(ProductionUnit("unit-1", "Main unit"), ProductionUnit("unit-2", "Second unit")),
    )

    /**
     * A view model with the queue read and request "a" open for review.
     *
     * The queue has to have answered first: the form opens on a request found
     * in the list, so opening it before the load lands is a no-op.
     */
    private fun TestScope.opened(
        repository: FakeRepository,
        loaded: ZillitResult<CrewPresets> = ZillitResult.Success(presets),
    ): ApprovalsViewModel {
        val approvals = ApprovalsViewModel(
            repository,
            presets = { loaded },
            nowMillis = { NOW },
        )
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()
        approvals.onEvent(ApprovalsEvent.Review.Open(ApprovalQueue.NewCrew, "a"))
        advanceUntilIdle()
        return approvals
    }

    @Test
    fun `the form opens on what the person asked for`() = runTest {
        // Reading it and pressing Approve is the common case; the pickers are
        // for the rest.
        val approvals = opened(FakeRepository(ZillitResult.Success(listOf(request("a")))))
        advanceUntilIdle()

        val review = approvals.state.value.review
        assertEquals("dept-1", review?.departmentId)
        assertEquals("role-1", review?.roleId)
        assertEquals("unit-1", review?.unitId)
    }

    @Test
    fun `changing department drops the role that belonged to the old one`() = runTest {
        // Keeping it would approve someone into a job their new department
        // does not have.
        val approvals = opened(FakeRepository(ZillitResult.Success(listOf(request("a")))))
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.Review.DepartmentChosen("dept-2"))

        assertEquals("dept-2", approvals.state.value.review?.departmentId)
        assertNull(approvals.state.value.review?.roleId)
    }

    @Test
    fun `a department with roles will not approve without one`() = runTest {
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"))))
        val approvals = opened(repository)
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.Review.DepartmentChosen("dept-2"))
        approvals.onEvent(ApprovalsEvent.Review.Approve)
        advanceUntilIdle()

        assertTrue(repository.decisions.isEmpty())
        assertEquals("Choose a role in Lighting.", approvals.state.value.review?.error)
    }

    @Test
    fun `a department with no roles approves without one`() = runTest {
        // Catering has none set up; blocking there would make the department
        // unusable rather than the form careful.
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"))))
        val approvals = opened(repository)
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.Review.DepartmentChosen("dept-3"))
        approvals.onEvent(ApprovalsEvent.Review.Approve)
        advanceUntilIdle()

        assertEquals(listOf(Triple(ApprovalQueue.NewCrew, "a", true)), repository.decisions)
    }

    @Test
    fun `approving sends the edited placement, not the requested one`() = runTest {
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"))))
        val approvals = opened(repository)
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.Review.DepartmentChosen("dept-2"))
        approvals.onEvent(ApprovalsEvent.Review.RoleChosen("role-2"))
        approvals.onEvent(ApprovalsEvent.Review.UnitChosen("unit-2"))
        approvals.onEvent(ApprovalsEvent.Review.PrivacyChanged(true))
        approvals.onEvent(ApprovalsEvent.Review.Approve)
        advanceUntilIdle()

        val sent = repository.decided.single()
        assertEquals("dept-2", sent.departmentId)
        assertEquals("Lighting", sent.departmentName)
        assertEquals("role-2", sent.designationId)
        assertEquals("unit-2", sent.unitId)
        assertTrue(sent.keepNamePrivate)
    }

    @Test
    fun `closing the form changes nothing`() = runTest {
        // An admin who edits a department and then closes has decided nothing,
        // and the row behind must still say what was asked for.
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"))))
        val approvals = opened(repository)
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.Review.DepartmentChosen("dept-2"))
        approvals.onEvent(ApprovalsEvent.Review.Close)
        advanceUntilIdle()

        assertNull(approvals.state.value.review)
        assertTrue(repository.decisions.isEmpty())
        assertEquals("dept-1", approvals.state.value.crew.items.single().departmentId)
    }

    @Test
    fun `declining from the form asks the same question the list does`() = runTest {
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"))))
        val approvals = opened(repository)
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.Review.Decline)
        advanceUntilIdle()

        assertNull(approvals.state.value.review)
        assertEquals("a", approvals.state.value.crew.confirming?.id)
        assertTrue(repository.decisions.isEmpty())
    }

    @Test
    fun `the choices are read once, on the first review`() = runTest {
        // Lazily: most visits to this page never open the form, and two calls
        // nobody needed is two calls on a production's wifi.
        var loads = 0
        val approvals = ApprovalsViewModel(
            FakeRepository(ZillitResult.Success(listOf(request("a"), request("b", "Sam Reed")))),
            presets = { loads++; ZillitResult.Success(presets) },
            nowMillis = { NOW },
        )
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        advanceUntilIdle()
        assertEquals(0, loads)

        approvals.onEvent(ApprovalsEvent.Review.Open(ApprovalQueue.NewCrew, "a"))
        advanceUntilIdle()
        approvals.onEvent(ApprovalsEvent.Review.Close)
        approvals.onEvent(ApprovalsEvent.Review.Open(ApprovalQueue.NewCrew, "b"))
        advanceUntilIdle()

        assertEquals(1, loads)
    }

    @Test
    fun `choices that could not be read do not block the decision`() = runTest {
        // Approving what the person asked for is the common case, and it does
        // not need a department list at all.
        val repository = FakeRepository(ZillitResult.Success(listOf(request("a"))))
        val approvals = opened(repository, loaded = ZillitResult.Failure(ZillitError.NoConnection("x")))
        advanceUntilIdle()

        assertTrue(approvals.state.value.presets.failed)

        approvals.onEvent(ApprovalsEvent.Review.Approve)
        advanceUntilIdle()

        assertEquals(listOf(Triple(ApprovalQueue.NewCrew, "a", true)), repository.decisions)
    }

    @Test
    fun `a decision that failed keeps the form open and says why`() = runTest {
        val repository = FakeRepository(
            listing = ZillitResult.Success(listOf(request("a"))),
            decision = ZillitResult.Failure(ZillitError.NoConnection("offline")),
        )
        val approvals = opened(repository)
        advanceUntilIdle()

        approvals.onEvent(ApprovalsEvent.Review.Approve)
        advanceUntilIdle()

        assertEquals("No internet connection.", approvals.state.value.review?.error)
        assertEquals(1, approvals.state.value.crew.items.size)
    }

    // -- what a row says ---------------------------------------------------

    @Test
    fun `a profile change is shown against what the crew list holds`() {
        val known = KnownCrewMember(fullName = "Aisha Khan", department = "Lighting", designation = "Focus Puller")

        val changes = request("a").changesAgainst(known)

        assertEquals(listOf("Department"), changes.map { it.label })
        assertEquals("Lighting", changes.single().from)
        assertEquals("Camera", changes.single().to)
    }

    @Test
    fun `somebody the crew list does not know shows no diff`() {
        // Better to show the requested values plainly than to invent a "from".
        assertTrue(request("a").changesAgainst(null).isEmpty())
    }

    @Test
    fun `a request with no name is still addressable`() {
        val nameless = PendingApproval(id = "a", userId = "u", fullName = "  ", email = "sam@prod.com")

        assertEquals("sam@prod.com", nameless.displayName)
    }

    @Test
    fun `waiting time is measured against when the list was read`() {
        assertEquals("asked today", waitedFor(NOW - HOUR, NOW))
        assertEquals("waiting 1 day", waitedFor(NOW - DAY, NOW))
        assertEquals("waiting 3 days", waitedFor(NOW - 3 * DAY, NOW))
        assertEquals("waiting 3 weeks", waitedFor(NOW - 21 * DAY, NOW))
    }

    @Test
    fun `a request from the future is not information`() {
        // Clock disagreement between a phone and this machine, not a queue age.
        assertNull(waitedFor(NOW + DAY, NOW))
        assertNull(waitedFor(null, NOW))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
    }
}
