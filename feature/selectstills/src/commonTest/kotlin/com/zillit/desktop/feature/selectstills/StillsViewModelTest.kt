@file:Suppress("MaxLineLength", "LongParameterList", "LargeClass")
// One tool, one view model, one test class: the gate, the two lists, the
// lightbox, the forms and a production switch all turn on the same state.

package com.zillit.desktop.feature.selectstills

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.feature.selectstills.domain.ApprovalRow
import com.zillit.desktop.feature.selectstills.domain.Client
import com.zillit.desktop.feature.selectstills.domain.Decision
import com.zillit.desktop.feature.selectstills.domain.DecisionAnswer
import com.zillit.desktop.feature.selectstills.domain.Member
import com.zillit.desktop.feature.selectstills.domain.Photo
import com.zillit.desktop.feature.selectstills.domain.PhotoKind
import com.zillit.desktop.feature.selectstills.domain.PhotoPage
import com.zillit.desktop.feature.selectstills.domain.PhotoStatus
import com.zillit.desktop.feature.selectstills.domain.PhotoTile
import com.zillit.desktop.feature.selectstills.domain.PublicState
import com.zillit.desktop.feature.selectstills.domain.ReviewCounts
import com.zillit.desktop.feature.selectstills.domain.ReviewPage
import com.zillit.desktop.feature.selectstills.domain.ReviewTab
import com.zillit.desktop.feature.selectstills.domain.SectionAllowance
import com.zillit.desktop.feature.selectstills.domain.StillsMe
import com.zillit.desktop.feature.selectstills.domain.StillsPick
import com.zillit.desktop.feature.selectstills.domain.StillsSettings
import com.zillit.desktop.feature.selectstills.domain.StillsSummary
import com.zillit.desktop.feature.selectstills.domain.StillsUrlCache
import com.zillit.desktop.feature.selectstills.domain.StillsViewer
import com.zillit.desktop.feature.selectstills.domain.Thresholds
import com.zillit.desktop.feature.selectstills.domain.ViewerScope
import com.zillit.desktop.feature.selectstills.ui.MeState
import com.zillit.desktop.feature.selectstills.ui.StillsEvent
import com.zillit.desktop.feature.selectstills.ui.StillsPage
import com.zillit.desktop.feature.selectstills.ui.StillsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * The tool's behaviour: the rights gate, the landing choice, what a filter
 * change asks for, and the run of decisions the queue is built for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StillsViewModelTest {

    private val repository = FakeStillsRepository()
    private var now = 0L

    private fun viewModel(viewer: StillsViewer = posting()) = StillsViewModel(
        repository = repository,
        viewer = { viewer },
        now = { now },
        urls = StillsUrlCache { now },
    )

    private fun posting() = StillsViewer(resolved = true, enabled = true, canView = true, canPost = true)
    private fun viewing() = StillsViewer(resolved = true, enabled = true, canView = true, canPost = false)

    private fun tile(id: String, sortAt: Long = 1, rows: List<ApprovalRow> = emptyList()) =
        PhotoTile(id = id, sortAt = sortAt, status = PhotoStatus.Done, myRows = rows)

    // -- the gate ----------------------------------------------------------------------

    @Test
    fun `nothing is asked of the service until the rights row has answered`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = StillsViewModel(repository = repository, viewer = { StillsViewer() }, now = { now })
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            // A 403 from the service is read app-wide as "no longer a member",
            // so an unanswered rights list must not produce a single call.
            assertTrue(repository.calls.isEmpty())
            assertEquals(MeState.Idle, vm.state.value.meState)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a tool switched off for the production asks for nothing either`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = viewModel(StillsViewer(resolved = true, enabled = false))
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            assertTrue(repository.calls.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `posting needs the rights row AND the service to agree`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.me = repository.me.copy(canPost = false)
            val vm = viewModel(posting())
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            // The row says yes, the service says no: no posting.
            assertFalse(vm.state.value.canPost)
            assertTrue(StillsPage.Upload !in vm.state.value.pages)
            assertTrue(StillsPage.Settings !in vm.state.value.pages)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a first me failure shows the retry panel, a later one keeps the screen`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.meFails = true
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            assertEquals(MeState.Error, vm.state.value.meState)

            repository.meFails = false
            vm.onEvent(StillsEvent.ReloadMe)
            advanceUntilIdle()
            assertEquals(MeState.Ready, vm.state.value.meState)

            // Ready once, so a later failure leaves what is on screen alone.
            repository.meFails = true
            vm.onEvent(StillsEvent.ReloadMe)
            advanceUntilIdle()
            assertEquals(MeState.Ready, vm.state.value.meState)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // -- what each reader is offered ---------------------------------------------------

    @Test
    fun `an agent who sees only their clients' photos gets the queue alone`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.me = StillsMe(
                userId = "u2",
                canPost = false,
                scope = ViewerScope.Clients,
                agentFor = listOf(Client("m1", "Anna")),
                settings = StillsSettings(attested = true),
            )
            repository.review = ReviewPage(counts = ReviewCounts(pending = 3))
            val vm = viewModel(viewing())
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()

            assertEquals(setOf(StillsPage.Review), vm.state.value.pages)
            // That is what the notification was about, so that is where it opens.
            assertEquals(StillsPage.Review, vm.state.value.page)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a production that opened the gallery gives a viewer the photos and the cast list`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.me = StillsMe(userId = "u3", canPost = false, scope = ViewerScope.Cleared, settings = StillsSettings())
            val vm = viewModel(viewing())
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()

            assertEquals(setOf(StillsPage.Photos, StillsPage.Cast), vm.state.value.pages)
            assertEquals(StillsPage.Photos, vm.state.value.page)
            // Cleared-only: a publication filter would be noise.
            assertFalse(vm.state.value.showsPublicationFilter)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `production lands on the gallery even with photos waiting on them`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.me = repository.me.copy(agentFor = listOf(Client("m1", "Anna")))
            repository.review = ReviewPage(counts = ReviewCounts(pending = 0))
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            assertEquals(StillsPage.Photos, vm.state.value.page)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `an agent with photos waiting opens straight into their queue`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.me = repository.me.copy(canPost = false, agentFor = listOf(Client("m1", "Anna")))
            repository.review = ReviewPage(counts = ReviewCounts(pending = 2))
            val vm = viewModel(viewing())
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            assertEquals(StillsPage.Review, vm.state.value.page)
            assertEquals(2, vm.state.value.reviewCounts.pending)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // -- the gallery -------------------------------------------------------------------

    @Test
    fun `a filter change asks the service again, and the answer that arrives last wins`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.gallery = PhotoPage(photos = listOf(tile("p1")), hasMore = true, next = "cur")
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            repository.calls.clear()

            vm.onEvent(StillsEvent.KindChanged(PhotoKind.Group))
            advanceUntilIdle()
            assertTrue(repository.calls.any { it.startsWith("photos(kind=Group") })
            assertEquals(PhotoKind.Group, vm.state.value.gallery.filters.kind)
            // A size is a refinement of Group, so changing the kind drops it.
            vm.onEvent(StillsEvent.GroupSizeChanged("3"))
            advanceUntilIdle()
            assertEquals("3", vm.state.value.gallery.filters.size)
            vm.onEvent(StillsEvent.KindChanged(PhotoKind.Solo))
            advanceUntilIdle()
            assertNull(vm.state.value.gallery.filters.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the shoot box waits for a pause in the typing before it filters`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            repository.calls.clear()

            vm.onEvent(StillsEvent.ShootTyped("d"))
            vm.onEvent(StillsEvent.ShootTyped("da"))
            vm.onEvent(StillsEvent.ShootTyped("day-12"))
            assertEquals("day-12", vm.state.value.gallery.shootText)
            // Still typing: nothing asked.
            assertTrue(repository.calls.none { it.startsWith("photos(") })

            advanceUntilIdle()
            assertEquals("day-12", vm.state.value.gallery.filters.shoot)
            assertEquals(1, repository.calls.count { it.startsWith("photos(") })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a socket frame asks for just the photos it names, with the filters as they are`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.gallery = PhotoPage(photos = listOf(tile("p1", sortAt = 20), tile("p2", sortAt = 10)))
            repository.galleryById = mapOf("p1" to PhotoPage(photos = listOf(tile("p1", sortAt = 20).copy(rev = 9))))
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            repository.calls.clear()

            // The gallery's own refresh path, as the socket drives it.
            vm.onEvent(StillsEvent.Open(StillsPage.Photos))
            advanceUntilIdle()
            assertEquals(listOf("p1", "p2"), vm.state.value.gallery.photos.map { it.id })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a failed gallery offers a retry rather than an empty page`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.galleryFails = true
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            vm.onEvent(StillsEvent.Open(StillsPage.Photos))
            advanceUntilIdle()
            assertTrue(vm.state.value.gallery.failed)

            repository.galleryFails = false
            repository.gallery = PhotoPage(photos = listOf(tile("p1")))
            vm.onEvent(StillsEvent.ReloadGallery)
            advanceUntilIdle()
            assertFalse(vm.state.value.gallery.failed)
            assertEquals(1, vm.state.value.gallery.photos.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // -- the queue ---------------------------------------------------------------------

    @Test
    fun `a decision takes the fresh allowances the service sent back`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.me = repository.me.copy(agentFor = listOf(Client("m1", "Anna", mapOf("3" to SectionAllowance(2, 0, 2)))))
            repository.review = ReviewPage(photos = listOf(tile("p1", rows = listOf(ApprovalRow("m1", canDecide = true)))))
            repository.decision = DecisionAnswer(
                photo = null,
                allowances = listOf(Client("m1", "Anna", mapOf("3" to SectionAllowance(2, 1, 1)))),
            )
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()

            vm.onEvent(StillsEvent.DecideOnCard("p1", "m1", Decision.Rejected, "eyes closed"))
            advanceUntilIdle()

            assertEquals(1, vm.state.value.clients.single().allowance["3"]?.remaining)
            assertTrue(repository.calls.any { it == "decide(p1, m1, rejected, note=eyes closed)" })
            // A run of decisions must not stack a toast per photo.
            assertNull(vm.state.value.notice)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a refused decision speaks, and the half-typed reason stays put`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.review = ReviewPage(photos = listOf(tile("p1", rows = listOf(ApprovalRow("m1", canDecide = true)))))
            repository.decisionError = ZillitError.Http(409, "still_kills_allowance_spent")
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()

            vm.onEvent(StillsEvent.CardDraft("p1", "eyes closed"))
            vm.onEvent(StillsEvent.DecideOnCard("p1", "m1", Decision.Rejected, "eyes closed"))
            advanceUntilIdle()

            // A refusal always speaks, quiet run or not.
            assertTrue(vm.state.value.error != null)
            // And the reason is still there to correct.
            assertEquals("p1", vm.state.value.review.draftFor)
            assertEquals("eyes closed", vm.state.value.review.draftNote)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `only an unfiltered queue sets the count beside the tab`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.me = repository.me.copy(agentFor = listOf(Client("m1", "Anna"), Client("m2", "Ben")))
            repository.review = ReviewPage(counts = ReviewCounts(pending = 5))
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            assertEquals(5, vm.state.value.reviewCounts.pending)

            // Narrowed to one actor: its counts are that actor's, not everyone's.
            repository.review = ReviewPage(counts = ReviewCounts(pending = 1))
            vm.onEvent(StillsEvent.ReviewMemberChanged("m1"))
            advanceUntilIdle()
            assertEquals(5, vm.state.value.reviewCounts.pending)
            assertEquals(1, vm.state.value.review.counts?.pending)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `changing the tab clears a half-typed reason and closes an open photo`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.review = ReviewPage(photos = listOf(tile("p1")))
            repository.photo = Photo(id = "p1", status = PhotoStatus.Done)
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            vm.onEvent(StillsEvent.OpenPhoto("p1", listOf("p1"), run = true))
            vm.onEvent(StillsEvent.CardDraft("p1", "half a reason"))
            advanceUntilIdle()

            vm.onEvent(StillsEvent.ReviewTabChanged(ReviewTab.Rejected))
            advanceUntilIdle()
            assertNull(vm.state.value.lightbox)
            assertNull(vm.state.value.review.draftFor)
            assertEquals(ReviewTab.Rejected, vm.state.value.review.tab)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // -- the lightbox ------------------------------------------------------------------

    @Test
    fun `a photo that is gone leaves the list and the next one opens`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.gallery = PhotoPage(photos = listOf(tile("p1", 20), tile("p2", 10)))
            // The service answers 404 for p1: it was deleted elsewhere.
            repository.photo = Photo(id = "p2", status = PhotoStatus.Done)
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            vm.onEvent(StillsEvent.Open(StillsPage.Photos))
            advanceUntilIdle()

            vm.onEvent(StillsEvent.OpenPhoto("p1", listOf("p1", "p2"), run = false))
            advanceUntilIdle()

            assertEquals("p2", vm.state.value.lightbox?.photoId)
            assertEquals(listOf("p2"), vm.state.value.gallery.photos.map { it.id })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `in a run, a settled photo moves on to the next by itself`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val settled = Photo(
                id = "p1",
                status = PhotoStatus.Done,
                publicState = PublicState.Approved,
                approvals = listOf(ApprovalRow("m1", state = Decision.Approved, canDecide = true)),
            )
            repository.photo = Photo(id = "p1", status = PhotoStatus.Done, approvals = listOf(ApprovalRow("m1", canDecide = true)))
            repository.decision = DecisionAnswer(photo = settled, allowances = null)
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()

            vm.onEvent(StillsEvent.OpenPhoto("p1", listOf("p1", "p2"), run = true))
            advanceUntilIdle()
            repository.photo = Photo(id = "p2", status = PhotoStatus.Done)

            vm.onEvent(StillsEvent.DecideInPhoto("m1", Decision.Approved))
            advanceUntilIdle()
            assertEquals("p2", vm.state.value.lightbox?.photoId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `in a run, a photo with a row still to decide stays put`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val half = Photo(
                id = "p1",
                status = PhotoStatus.Done,
                approvals = listOf(
                    ApprovalRow("m1", state = Decision.Approved, canDecide = true),
                    ApprovalRow("m2", state = Decision.Pending, canDecide = true),
                ),
            )
            repository.photo = half
            repository.decision = DecisionAnswer(photo = half, allowances = null)
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            vm.onEvent(StillsEvent.OpenPhoto("p1", listOf("p1", "p2"), run = true))
            advanceUntilIdle()

            vm.onEvent(StillsEvent.DecideInPhoto("m1", Decision.Approved))
            advanceUntilIdle()
            // Their other actor is still waiting: one pair of buttons cannot
            // answer for two people, so the photo stays open.
            assertEquals("p1", vm.state.value.lightbox?.photoId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the D key opens the reason box only when there is one row to decide`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.photo = Photo(
                id = "p1",
                status = PhotoStatus.Done,
                approvals = listOf(ApprovalRow("m1", canDecide = true), ApprovalRow("m2", canDecide = true)),
            )
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            vm.onEvent(StillsEvent.OpenPhoto("p1", listOf("p1"), run = true))
            advanceUntilIdle()

            vm.onEvent(StillsEvent.AskWhy)
            assertFalse(vm.state.value.lightbox?.asking == true)

            repository.photo = Photo(id = "p1", status = PhotoStatus.Done, approvals = listOf(ApprovalRow("m1", canDecide = true)))
            vm.onEvent(StillsEvent.ReloadPhoto)
            advanceUntilIdle()
            vm.onEvent(StillsEvent.AskWhy)
            assertTrue(vm.state.value.lightbox?.asking == true)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `deleting a photo takes it off the grid`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.gallery = PhotoPage(photos = listOf(tile("p1", 20)))
            repository.photo = Photo(id = "p1", status = PhotoStatus.Done)
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            vm.onEvent(StillsEvent.Open(StillsPage.Photos))
            advanceUntilIdle()
            vm.onEvent(StillsEvent.OpenPhoto("p1", listOf("p1"), run = false))
            advanceUntilIdle()

            vm.onEvent(StillsEvent.DeletePhoto)
            advanceUntilIdle()
            assertTrue(vm.state.value.gallery.photos.isEmpty())
            assertNull(vm.state.value.lightbox)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // -- settings ----------------------------------------------------------------------

    @Test
    fun `settings save only what changed, and nothing at all when nothing did`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.me = repository.me.copy(
                settings = StillsSettings(attested = true, viewerScope = ViewerScope.Cleared, thresholds = Thresholds(80, 60, 5)),
            )
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            repository.calls.clear()

            vm.onEvent(StillsEvent.SaveSettings)
            advanceUntilIdle()
            assertTrue(repository.calls.none { it.startsWith("updateSettings") })

            vm.onEvent(StillsEvent.SettingsScope(ViewerScope.All))
            vm.onEvent(StillsEvent.SaveSettings)
            advanceUntilIdle()
            assertTrue(repository.calls.any { it == "updateSettings(scope=All, limits=null, numbers=null)" })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `numbers outside the scale, or a suggest above the automatic, never leave`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            repository.calls.clear()

            vm.onEvent(StillsEvent.SettingsNumber("auto", "60"))
            vm.onEvent(StillsEvent.SettingsNumber("suggest", "90"))
            vm.onEvent(StillsEvent.SaveSettings)
            advanceUntilIdle()
            assertTrue(repository.calls.none { it.startsWith("updateSettings") })

            vm.onEvent(StillsEvent.SettingsNumber("auto", "900"))
            vm.onEvent(StillsEvent.SettingsNumber("suggest", "60"))
            vm.onEvent(StillsEvent.SaveSettings)
            advanceUntilIdle()
            assertTrue(repository.calls.none { it.startsWith("updateSettings") })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the face-data notice has to be acknowledged before anything is enrolled`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.me = repository.me.copy(settings = StillsSettings(attested = false))
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()

            vm.onEvent(StillsEvent.EnrollName("Anna"))
            vm.onEvent(StillsEvent.EnrollConsent(true))
            vm.onEvent(StillsEvent.Enrol)
            advanceUntilIdle()
            assertTrue(repository.calls.none { it.startsWith("createMember") })

            vm.onEvent(StillsEvent.AcceptNotice)
            advanceUntilIdle()
            vm.onEvent(StillsEvent.Enrol)
            advanceUntilIdle()
            assertTrue(repository.calls.any { it == "createMember(Anna)" })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `enrolling needs a name and their agreement, and clears the card after`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()

            // No name.
            vm.onEvent(StillsEvent.EnrollConsent(true))
            vm.onEvent(StillsEvent.Enrol)
            advanceUntilIdle()
            assertTrue(repository.calls.none { it.startsWith("createMember") })

            // Named, but nobody agreed.
            vm.onEvent(StillsEvent.EnrollConsent(false))
            vm.onEvent(StillsEvent.EnrollName("Anna"))
            vm.onEvent(StillsEvent.Enrol)
            advanceUntilIdle()
            assertTrue(repository.calls.none { it.startsWith("createMember") })

            vm.onEvent(StillsEvent.EnrollConsent(true))
            vm.onEvent(StillsEvent.Enrol)
            advanceUntilIdle()
            assertTrue(repository.calls.any { it == "createMember(Anna)" })
            assertEquals("", vm.state.value.enroll?.name)
            assertFalse(vm.state.value.enroll?.consent == true)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // -- the member's card -------------------------------------------------------------

    @Test
    fun `a card saves the basics, the agent and the allowance in the web's order`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.members = listOf(Member(id = "m1", name = "Anna"))
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            vm.onEvent(StillsEvent.OpenMember("m1"))
            advanceUntilIdle()
            repository.calls.clear()

            vm.onEvent(StillsEvent.MemberName("Anna Bell"))
            vm.onEvent(StillsEvent.MemberAgentPicked("u9"))
            vm.onEvent(StillsEvent.MemberLimits(mapOf("1" to "2")))
            vm.onEvent(StillsEvent.SaveMember)
            advanceUntilIdle()

            val writes = repository.calls.filter { it.startsWith("updateMember") || it.startsWith("setMemberAgent") || it.startsWith("setMemberLimits") }
            assertEquals(3, writes.size)
            assertTrue(writes[0].startsWith("updateMember"))
            assertTrue(writes[1].startsWith("setMemberAgent"))
            assertTrue(writes[2].startsWith("setMemberLimits"))
            // The card closes once it saved.
            assertNull(vm.state.value.memberDialog)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `turning approval off sends a null agent and no allowance at all`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.members = listOf(Member(id = "m1", name = "Anna", agentUserId = "u9", discardLimits = mapOf("1" to 2)))
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            vm.onEvent(StillsEvent.OpenMember("m1"))
            advanceUntilIdle()
            repository.calls.clear()

            vm.onEvent(StillsEvent.MemberApproval(false))
            vm.onEvent(StillsEvent.SaveMember)
            advanceUntilIdle()

            assertTrue(repository.calls.any { it == "setMemberAgent(m1, null)" })
            assertTrue(repository.calls.none { it.startsWith("setMemberLimits") })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `removing a member who is named in photos asks again before forcing it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.members = listOf(Member(id = "m1", name = "Anna", photoCount = 4))
            repository.removeError = ZillitError.Http(409, "still_kills_member_in_use")
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            vm.onEvent(StillsEvent.OpenMember("m1"))
            advanceUntilIdle()

            vm.onEvent(StillsEvent.RemoveMember(false))
            advanceUntilIdle()
            assertEquals(com.zillit.desktop.feature.selectstills.ui.MemberAsk.RemoveInUse, vm.state.value.memberDialog?.ask)
            // The card is still open: nothing was removed yet.
            assertEquals(1, vm.state.value.members.size)

            vm.onEvent(StillsEvent.RemoveMember(true))
            advanceUntilIdle()
            assertNull(vm.state.value.memberDialog)
            assertTrue(vm.state.value.members.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a headshot's x asks for a second press before it removes anything`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.members = listOf(
                Member(
                    id = "m1",
                    name = "Anna",
                    headshots = listOf(com.zillit.desktop.feature.selectstills.domain.Headshot("h1", "https://s/h1")),
                ),
            )
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            vm.onEvent(StillsEvent.OpenMember("m1"))
            advanceUntilIdle()

            vm.onEvent(StillsEvent.RemoveHeadshot("h1"))
            advanceUntilIdle()
            assertEquals("h1", vm.state.value.memberDialog?.confirmShot)
            vm.onEvent(StillsEvent.RemoveHeadshot("h1"))
            advanceUntilIdle()
            assertNull(vm.state.value.memberDialog?.confirmShot)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `headshots dropped on a card are walked through, folders included`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val card = StillsPick("/card/a.jpg", "a.jpg", 10, "image/jpeg")
            val roll = StillsPick("/roll/b.jpg", "b.jpg", 10, "image/jpeg")
            val vm = StillsViewModel(
                repository = repository,
                viewer = { posting() },
                files = FakeStillsFiles(mapOf("/card/a.jpg" to listOf(card), "/roll" to listOf(roll))),
                now = { now },
            )
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()

            // One file and one folder, dropped together.
            vm.onEvent(StillsEvent.DropEnrollPaths(listOf("/card/a.jpg", "/roll")))
            advanceUntilIdle()
            assertEquals(listOf("a.jpg", "b.jpg"), vm.state.value.enroll?.picks?.map { it.name })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `at most five angles are kept, however many are dropped`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val many = (1..8).map { StillsPick("/roll/$it.jpg", "$it.jpg", 10, "image/jpeg") }
            val vm = StillsViewModel(
                repository = repository,
                viewer = { posting() },
                files = FakeStillsFiles(mapOf("/roll" to many)),
                now = { now },
            )
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            vm.onEvent(StillsEvent.DropEnrollPaths(listOf("/roll")))
            advanceUntilIdle()
            assertEquals(5, vm.state.value.enroll?.picks?.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // -- a production switch -----------------------------------------------------------

    @Test
    fun `another production keeps none of the one that was open`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            repository.gallery = PhotoPage(photos = listOf(tile("p1")))
            repository.members = listOf(Member(id = "m1", name = "Anna"))
            repository.summary = StillsSummary(total = 1)
            val vm = viewModel()
            vm.onEvent(StillsEvent.Load)
            advanceUntilIdle()
            vm.onEvent(StillsEvent.Open(StillsPage.Photos))
            advanceUntilIdle()
            assertEquals(1, vm.state.value.gallery.photos.size)
            assertEquals(1, vm.state.value.members.size)

            // The next production's answers.
            repository.gallery = PhotoPage()
            repository.members = emptyList()
            repository.summary = StillsSummary(total = 0)
            vm.onProjectChanged()
            advanceUntilIdle()

            // One shoot's stills must never show under another's name.
            assertTrue(vm.state.value.gallery.photos.isEmpty())
            assertTrue(vm.state.value.members.isEmpty())
            assertEquals(0, vm.state.value.gallery.summary?.total)
            assertNull(vm.state.value.lightbox)
            assertNull(vm.state.value.memberDialog)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
