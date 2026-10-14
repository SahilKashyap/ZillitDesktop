@file:Suppress("MaxLineLength", "LongParameterList", "LongMethod", "LargeClass")
// One fixture carrying every state the pages draw from; splitting it would
// mean five near-copies that drift apart.

package com.zillit.desktop.feature.selectstills

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.selectstills.data.HeadshotOutcome
import com.zillit.desktop.feature.selectstills.data.PutOutcome
import com.zillit.desktop.feature.selectstills.data.StillsUploadQueue
import com.zillit.desktop.feature.selectstills.data.StillsUploader
import com.zillit.desktop.feature.selectstills.domain.ApprovalRow
import com.zillit.desktop.feature.selectstills.domain.Client
import com.zillit.desktop.feature.selectstills.domain.Decision
import com.zillit.desktop.feature.selectstills.domain.Face
import com.zillit.desktop.feature.selectstills.domain.FaceBox
import com.zillit.desktop.feature.selectstills.domain.FaceState
import com.zillit.desktop.feature.selectstills.domain.Headshot
import com.zillit.desktop.feature.selectstills.domain.Member
import com.zillit.desktop.feature.selectstills.domain.Photo
import com.zillit.desktop.feature.selectstills.domain.PhotoStatus
import com.zillit.desktop.feature.selectstills.domain.PhotoTile
import com.zillit.desktop.feature.selectstills.domain.PublicState
import com.zillit.desktop.feature.selectstills.domain.Recognition
import com.zillit.desktop.feature.selectstills.domain.ReviewCounts
import com.zillit.desktop.feature.selectstills.domain.SectionAllowance
import com.zillit.desktop.feature.selectstills.domain.StillsMe
import com.zillit.desktop.feature.selectstills.domain.StillsPick
import com.zillit.desktop.feature.selectstills.domain.StillsSettings
import com.zillit.desktop.feature.selectstills.domain.StillsSummary
import com.zillit.desktop.feature.selectstills.domain.StillsViewer
import com.zillit.desktop.feature.selectstills.domain.SummaryMember
import com.zillit.desktop.feature.selectstills.domain.Thresholds
import com.zillit.desktop.feature.selectstills.domain.ViewerScope
import com.zillit.desktop.feature.selectstills.ui.CastState
import com.zillit.desktop.feature.selectstills.ui.EnrollState
import com.zillit.desktop.feature.selectstills.ui.GalleryState
import com.zillit.desktop.feature.selectstills.ui.LightboxState
import com.zillit.desktop.feature.selectstills.ui.MeState
import com.zillit.desktop.feature.selectstills.ui.MemberDialogState
import com.zillit.desktop.feature.selectstills.ui.ReviewState
import com.zillit.desktop.feature.selectstills.ui.SettingsFormState
import com.zillit.desktop.feature.selectstills.ui.SimilarState
import com.zillit.desktop.feature.selectstills.ui.StillsEvent
import com.zillit.desktop.feature.selectstills.ui.StillsPage
import com.zillit.desktop.feature.selectstills.ui.StillsPerson
import com.zillit.desktop.feature.selectstills.ui.StillsScreen
import com.zillit.desktop.feature.selectstills.ui.StillsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * Composes every page of the real screen in each state.
 *
 * This is the test that catches a layout which throws rather than draws — a
 * lazy grid in a scrolling parent, a divider that blanks a row — which no unit
 * test can see. Assertions use `assertExists`, not `assertIsDisplayed`: the
 * test window is small, and anything below the first screenful is composed but
 * not shown.
 */
@OptIn(ExperimentalTestApi::class)
class StillsScreenRenderTest {



    /** Wider than any plausible pair of neighbouring controls. */
    private val minArrowGap = 400f

    private val crew = listOf(
        StillsPerson("u1", "Una One", "Stills Photographer", "Camera"),
        StillsPerson("u9", "Nina Nine", "Agent", "Production"),
    )

    private val members = listOf(
        Member(
            id = "m1",
            name = "Anna Bell",
            characterName = "Mira",
            headshots = listOf(Headshot("h1", "https://s/h1")),
            headshotUrl = "https://s/h1",
            agentUserId = "u9",
            discardLimits = mapOf("1" to 2),
            allowance = mapOf("1" to SectionAllowance(2, 1, 1)),
            photoCount = 12,
        ),
        // Nobody has to approve: marked loudly, because photos of them are cleared unasked.
        Member(id = "m2", name = "Ben Bold", approvalRequired = false, recognition = Recognition.SuggestOnly),
        // Needs approval and has no agent: every photo they are in is held.
        Member(id = "m3", name = "Cal Clay"),
    )

    private fun tile(id: String, name: String, state: PublicState = PublicState.Pending, unnamed: Int = 0) = PhotoTile(
        id = id,
        sortAt = 10,
        status = PhotoStatus.Done,
        originalName = name,
        publicState = state,
        hasApprovals = true,
        people = 2,
        names = listOf("Anna Bell"),
        moreNames = 1,
        unnamed = unnamed,
    )

    private fun state(
        page: StillsPage,
        canPost: Boolean = true,
        scope: ViewerScope = ViewerScope.All,
    ) = StillsUiState(
        viewer = StillsViewer(resolved = true, enabled = true, canView = true, canPost = canPost),
        page = page,
        landed = true,
        meState = MeState.Ready,
        me = StillsMe(
            userId = "u1",
            canPost = canPost,
            isAdmin = true,
            scope = scope,
            agentFor = listOf(Client("m1", "Anna Bell", mapOf("1" to SectionAllowance(2, 2, 0)))),
            settings = StillsSettings(attested = true, viewerScope = scope, thresholds = Thresholds(82, 65, 8)),
            storageSupported = true,
            regionSupported = true,
            upload = com.zillit.desktop.feature.selectstills.domain.UploadLimits(listOf("image/jpeg"), 52_428_800),
            queueWaiting = 3,
        ),
        crew = crew,
        members = members,
        membersLoaded = true,
        reviewCounts = ReviewCounts(pending = 7, approved = 9, rejected = 2, all = 15),
        reviewReady = true,
        gallery = GalleryState(
            photos = listOf(
                tile("p1", "A001.jpg", PublicState.Approved),
                tile("p2", "A002.jpg", PublicState.Blocked, unnamed = 2),
                PhotoTile(id = "p3", status = PhotoStatus.Failed, errorCode = "too_large"),
                PhotoTile(id = "p4", status = PhotoStatus.Processing),
            ),
            hasMore = true,
            loading = false,
            summary = StillsSummary(
                total = 90,
                bySection = mapOf("1" to 10, "2" to 20),
                needsNames = 4,
                processing = 3,
                failed = 1,
                approved = 40,
                pending = 30,
                blocked = 20,
                members = listOf(SummaryMember("m1", "Anna Bell", 12)),
                shoots = listOf("day-12"),
            ),
        ),
        review = ReviewState(
            photos = listOf(
                PhotoTile(
                    id = "p1",
                    status = PhotoStatus.Done,
                    originalName = "A001.jpg",
                    people = 3,
                    section = "3",
                    myRows = listOf(ApprovalRow("m1", "Anna Bell", canDecide = true)),
                    others = listOf(ApprovalRow("m2", "Ben Bold", state = Decision.Rejected)),
                ),
            ),
            counts = ReviewCounts(pending = 4, approved = 9, rejected = 2, all = 15),
            loading = false,
        ),
        settings = SettingsFormState(scope = scope, limits = mapOf("1" to "2"), auto = "82", suggest = "65", margin = "8"),
        enroll = EnrollState(),
    )

    private val photo = Photo(
        id = "p1",
        status = PhotoStatus.Done,
        width = 6000,
        height = 4000,
        previewUrl = "https://s/preview",
        originalName = "A001.jpg",
        shootLabel = "day-12",
        createdMillis = 1_760_000_000_000,
        uploadedBy = "u1",
        publicState = PublicState.Pending,
        people = 3,
        unnamed = 1,
        truncated = true,
        section = "3",
        faces = listOf(
            Face(id = "f1", memberId = "m1", name = "Anna Bell", state = FaceState.Matched, similarity = 91f, box = FaceBox(0.1f, 0.1f, 0.2f, 0.3f), hasVector = true),
            Face(id = "f2", memberId = "m2", name = "Ben Bold", state = FaceState.Suggested, similarity = 68f, box = FaceBox(0.4f, 0.1f, 0.2f, 0.3f)),
            Face(id = "f3", state = FaceState.Unknown, box = FaceBox(0.7f, 0.1f, 0.2f, 0.3f)),
            Face(id = "f4", memberId = "m3", name = "Cal Clay", state = FaceState.Tagged, manual = true),
            Face(id = "f5", state = FaceState.Dismissed, box = FaceBox(0.1f, 0.6f, 0.1f, 0.2f)),
        ),
        approvals = listOf(
            ApprovalRow("m1", "Anna Bell", state = Decision.Pending, canDecide = true, agentUserId = "u9"),
            ApprovalRow("m3", "Cal Clay", state = Decision.Rejected, note = "eyes closed", hasAgent = false),
        ),
    )

    // -- the shell ---------------------------------------------------------------------

    @Test
    fun `the top bar draws the pages a posting user gets, in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { StillsScreen(state(StillsPage.Photos), onEvent = {}) } }
                // The film industry's words (the web, 9 October).
                onNodeWithText("Cast").assertExists()
                onNodeWithText("Upload").assertExists()
                onNodeWithText("Approvals").assertExists()
                onNodeWithText("Settings").assertExists()
                // No wordmark: inside Zillit it read as a second logo.
                onAllNodesWithText("Select Stills").assertCountEquals(0)
                // How many photos wait on this person, beside "Approvals".
                onNodeWithText("7", substring = true).assertExists()
            }
        }
    }

    /**
     * `assertIsDisplayed`, not `assertExists`: the bug this guards against was a
     * `fillMaxWidth` underline inside the bar's Row, which gave the first
     * destination the whole width and pushed the other four off the end. Every
     * one of them still *existed* — they were composed, just nowhere.
     */
    @Test
    fun `every destination the reader has is actually on the bar, not just composed`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = true) { StillsScreen(state(StillsPage.Photos), onEvent = {}) } }
            listOf("Cast", "Upload", "Approvals").forEach {
                onNodeWithText(it).assertIsDisplayed()
            }
            // "Stills" and "Settings" are each on the bar and on their page, so
            // the bar's one is the first of the two.
            onAllNodesWithText("Stills").onFirst().assertIsDisplayed()
            onAllNodesWithText("Settings").onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun `a reader who can only view is offered no upload, no settings and no cast list`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(state(StillsPage.Photos, canPost = false, scope = ViewerScope.Cleared), onEvent = {}) } }
            onAllNodesWithText("Upload").assertCountEquals(0)
            onAllNodesWithText("Settings").assertCountEquals(0)
            // The cast list is production's: a viewer was shown everybody by name.
            onAllNodesWithText("Cast").assertCountEquals(0)
            // The bar's link and the page's own heading both say "Stills".
            onAllNodesWithText("Stills").assertCountEquals(2)
            // Nor may they pick photos to delete.
            onAllNodesWithText("Select photos").assertCountEquals(0)
        }
    }

    @Test
    fun `a viewer sent to the cast page gets the gallery instead`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(state(StillsPage.Cast, canPost = false, scope = ViewerScope.Cleared), onEvent = {}) } }
            onAllNodesWithText("Enrolled cast members", substring = true).assertCountEquals(0)
            onNodeWithText("Browse the photos that are cleared. Click a cast member to see every photo they appear in.").assertExists()
        }
    }

    @Test
    fun `a production without the tool is told so rather than shown a page`() {
        runComposeUiTest {
            val off = state(StillsPage.Photos).copy(viewer = StillsViewer(resolved = true, enabled = false))
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(off, onEvent = {}) } }
            onNodeWithText("Select Stills is not switched on for this production.").assertExists()
        }
    }

    @Test
    fun `a reader without view rights is told so, with a way to ask`() {
        runComposeUiTest {
            val denied = state(StillsPage.Photos).copy(viewer = StillsViewer(resolved = true, enabled = true, canView = false))
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(denied, onEvent = {}) } }
            onNodeWithText("You do not have access to Select Stills.").assertExists()
        }
    }

    @Test
    fun `a me that could not be loaded offers a retry`() {
        runComposeUiTest {
            val broken = state(StillsPage.Photos).copy(meState = MeState.Error)
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(broken, onEvent = {}) } }
            onNodeWithText("Select Stills could not be loaded.").assertExists()
            onNodeWithText("Try again").assertExists()
        }
    }

    // -- the gallery -------------------------------------------------------------------

    @Test
    fun `the gallery draws its filters, its counts and its tiles`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { StillsScreen(state(StillsPage.Photos), onEvent = {}) } }
                onNodeWithText("All photos").assertExists()
                onNodeWithText("👤 Solo").assertExists()
                onNodeWithText("👥 Group").assertExists()
                // Only production is offered these two.
                onNodeWithText("Processing").assertExists()
                onNodeWithText("Failed").assertExists()
                onNodeWithText("Everyone").assertExists()
                // Once in the by-member list (with her count), once on the tile.
                onAllNodesWithText("Anna Bell", substring = true).assertCountEquals(3)
                // Where each photo stands, and who is in it.
                onNodeWithText("✓ cleared").assertExists()
                onNodeWithText("✕ killed").assertExists()
                onNodeWithText("2 unknown").assertExists()
                onNodeWithText("failed").assertExists()
                // Production can clear photos out in bulk.
                onNodeWithText("Select photos").assertExists()
            }
        }
    }

    @Test
    fun `selecting photos counts what is picked and offers one delete for all of it`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                val picking = state(StillsPage.Photos).let { it.copy(gallery = it.gallery.copy(picking = true, picked = setOf("p1", "p2"))) }
                setContent { ZillitTheme(darkTheme = dark) { StillsScreen(picking, onEvent = {}) } }
                onNodeWithText("2 selected").assertExists()
                onNodeWithText("Select all shown (4)").assertExists()
                onNodeWithText("Clear selection").assertExists()
                onNodeWithText("Delete 2 photos").assertExists()
                onNodeWithText("Done").assertExists()
                onAllNodesWithText("Select photos").assertCountEquals(0)
                // Picking, a tile is a tick box: the ticked ones say so.
                onAllNodesWithText("✓").assertCountEquals(2)
            }
        }
    }

    @Test
    fun `with nothing picked the bar says how, and Delete waits`() {
        runComposeUiTest {
            val picking = state(StillsPage.Photos).let { it.copy(gallery = it.gallery.copy(picking = true)) }
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(picking, onEvent = {}) } }
            onNodeWithText("0 selected").assertExists()
            onNodeWithText("Click photos to select them. Shift-click takes everything in between.").assertExists()
            onAllNodesWithText("Clear selection").assertCountEquals(0)
        }
    }

    @Test
    fun `a bulk delete asks once, and counts as it goes`() {
        runComposeUiTest {
            val asking = state(StillsPage.Photos).let {
                it.copy(gallery = it.gallery.copy(picking = true, picked = setOf("p1", "p2", "p3"), askingDelete = true, removing = 1 to 3))
            }
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(asking, onEvent = {}) } }
            onNodeWithText("Delete these 3 photos?").assertExists()
            onNodeWithText("They leave the gallery for everybody, and the face data found in them is deleted. This cannot be undone from here.").assertExists()
            onNodeWithText("Deleting 1 of 3…").assertExists()
        }
    }

    @Test
    fun `a cleared-only reader gets no publication filter and no counts of what they cannot see`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(state(StillsPage.Photos, canPost = false, scope = ViewerScope.Cleared), onEvent = {}) } }
            onAllNodesWithText("Publication").assertCountEquals(0)
            onAllNodesWithText("◷ Awaiting").assertCountEquals(0)
            // Nor the "4 photos with unknown faces" line.
            onAllNodesWithText("4 photos with unknown faces").assertCountEquals(0)
        }
    }

    @Test
    fun `the group sizes appear only while Group is chosen`() {
        runComposeUiTest {
            val grouped = state(StillsPage.Photos).let {
                it.copy(gallery = it.gallery.copy(filters = it.gallery.filters.copy(kind = com.zillit.desktop.feature.selectstills.domain.PhotoKind.Group)))
            }
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(grouped, onEvent = {}) } }
            onNodeWithText("Any size").assertExists()
            onNodeWithText("2 people").assertExists()
            onNodeWithText("6 or more").assertExists()
        }
    }

    /**
     * The photos flow round the cast list: beside it while it lasts, the whole
     * width once it ends — two across and then three on a 1000-wide tool. The
     * grid used to be a LazyVerticalGrid the whole page lived in, where the list
     * could only be a full-width row, so the first photo started under the
     * list's last name with the page beside the list left empty.
     */
    @Test
    fun `the photos sit beside the cast list, then take the whole width under it`() {
        val cast = (1..14).map { SummaryMember("c$it", "Cast Member $it", 20 - it) }
        val long = state(StillsPage.Photos).let {
            it.copy(
                gallery = it.gallery.copy(
                    photos = (1..12).map { n -> PhotoTile(id = "f$n", status = PhotoStatus.Failed, errorCode = "too_large") },
                    summary = it.gallery.summary!!.copy(members = cast),
                ),
            )
        }
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    Box(Modifier.requiredSize(1000.dp, 3000.dp)) { StillsScreen(long, onEvent = {}) }
                }
            }
            val everyone = onNodeWithText("Everyone").fetchSemanticsNode().boundsInRoot
            val last = onNodeWithText("Cast Member 14").fetchSemanticsNode().boundsInRoot
            // One "failed" pill in each tile's top corner.
            val pills = onAllNodesWithText("failed").fetchSemanticsNodes().map { it.boundsInRoot }
            assertEquals(12, pills.size)
            val beside = pills.filter { it.top < last.top }
            val under = pills.filter { it.top > last.bottom }
            assertTrue(beside.isNotEmpty() && beside.all { it.left > everyone.right }, "beside the list: $beside, the list at $everyone")
            assertTrue(under.isNotEmpty() && under.any { it.left < everyone.right }, "under the list, from the page's edge: $under")
            assertEquals(2, beside.map { it.right.toInt() }.distinct().size, "two across beside the list: $beside")
            assertEquals(3, under.map { it.right.toInt() }.distinct().size, "three across under it: $under")
        }
    }

    /** `VirtuosoGrid` with `overscan={800}`: a shoot of thousands composes the rows in view, and its end loads the next page. */
    @Test
    fun `a long gallery composes only the rows in view, and asks for more at its end`() {
        val many = state(StillsPage.Photos).let { base ->
            base.copy(gallery = base.gallery.copy(photos = (1..300).map { PhotoTile(id = "p$it", status = PhotoStatus.Processing) }))
        }
        var asked = 0
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    Box(Modifier.requiredSize(1400.dp, 900.dp)) {
                        StillsScreen(many, onEvent = { if (it == StillsEvent.LoadMorePhotos) asked += 1 })
                    }
                }
            }
            // Each tile says "processing…" twice: its pill and its placeholder.
            val atTop = onAllNodesWithText("processing…").fetchSemanticsNodes().size / 2
            assertTrue(atTop in 1..60, "$atTop of 300 tiles composed at the top")
            assertEquals(0, asked, "nothing more is asked for while the end is out of sight")

            repeat(200) { onRoot().performMouseInput { scroll(400f) } }
            waitForIdle()
            val atEnd = onAllNodesWithText("processing…").fetchSemanticsNodes().size / 2
            assertTrue(atEnd in 1..60, "$atEnd of 300 tiles composed at the end")
            assertTrue(asked > 0, "the end of the grid asks for the next page")
        }
    }

    @Test
    fun `an empty gallery says so instead of drawing nothing`() {
        runComposeUiTest {
            val empty = state(StillsPage.Photos).let { it.copy(gallery = it.gallery.copy(photos = emptyList(), loading = false)) }
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(empty, onEvent = {}) } }
            onNodeWithText("No photos here yet.").assertExists()
        }
    }

    @Test
    fun `a gallery that could not load offers a retry`() {
        runComposeUiTest {
            val failed = state(StillsPage.Photos).let { it.copy(gallery = it.gallery.copy(photos = emptyList(), loading = false, failed = true)) }
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(failed, onEvent = {}) } }
            onNodeWithText("The photos could not be loaded.").assertExists()
        }
    }

    // -- the queue ---------------------------------------------------------------------

    @Test
    fun `the queue draws its tabs, who the reader answers for, and a card`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { StillsScreen(state(StillsPage.Review), onEvent = {}) } }
                onNodeWithText("To decide").assertExists()
                onNodeWithText("Approved").assertExists()
                onNodeWithText("Killed").assertExists()
                onNodeWithText("Everything").assertExists()
                onNodeWithText("Represents").assertExists()
                onNodeWithText("✓ Approve").assertExists()
                onNodeWithText("✕ Kill").assertExists()
                // The other agent's kill, and what it means for this one.
                onNodeWithText("Ben Bold: killed").assertExists()
                onNodeWithText("Already killed by another agent, so this will not be cleared even if you approve it.").assertExists()
            }
        }
    }

    @Test
    fun `an allowance that is spent says so on the card`() {
        runComposeUiTest {
            val spent = state(StillsPage.Review).let {
                it.copy(
                    clientsOverride = listOf(Client("m1", "Anna Bell", mapOf("3" to SectionAllowance(2, 2, 0)))),
                    review = it.review.copy(photos = it.review.photos.map { photo -> photo.copy(others = emptyList()) }),
                )
            }
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(spent, onEvent = {}) } }
            // On the card only: over the queue it is behind an (i), so the
            // page starts with the photos rather than five lines of numbers.
            onAllNodesWithText("3 people: no kills left, all 2 used").assertCountEquals(1)
            onNodeWithContentDescription("Show what is left of the kill allowance").assertExists()
        }
    }

    @Test
    fun `a photo with two of the reader's actors is opened to be decided`() {
        runComposeUiTest {
            val two = state(StillsPage.Review).let {
                it.copy(
                    review = it.review.copy(
                        photos = listOf(
                            PhotoTile(
                                id = "p1",
                                status = PhotoStatus.Done,
                                people = 4,
                                myRows = listOf(ApprovalRow("m1", "Anna Bell", canDecide = true), ApprovalRow("m2", "Ben Bold", canDecide = true)),
                            ),
                        ),
                    ),
                )
            }
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(two, onEvent = {}) } }
            onNodeWithText("Open to decide").assertExists()
            // One pair of buttons cannot answer for two people.
            onAllNodesWithText("✓ Approve").assertCountEquals(0)
        }
    }

    @Test
    fun `an empty queue says which tab is empty`() {
        runComposeUiTest {
            val empty = state(StillsPage.Review).let { it.copy(review = it.review.copy(photos = emptyList(), loading = false)) }
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(empty, onEvent = {}) } }
            onNodeWithText("Nothing waiting on you.").assertExists()
        }
    }

    @Test
    fun `an agent who answers for nobody is told so rather than shown an empty list`() {
        runComposeUiTest {
            val nobody = state(StillsPage.Review).let {
                it.copy(
                    me = it.me.copy(agentFor = emptyList()),
                    clientsOverride = emptyList(),
                    review = it.review.copy(photos = emptyList(), loading = false),
                )
            }
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(nobody, onEvent = {}) } }
            onNodeWithText("nobody on this production yet").assertExists()
        }
    }

    // -- the lightbox ------------------------------------------------------------------

    @Test
    fun `the gallery's lightbox draws the gate, the faces and production's tools`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                val open = state(StillsPage.Photos).copy(
                    lightbox = LightboxState(photoId = "p1", ids = listOf("p1", "p2"), run = false, photo = photo, loading = false),
                )
                setContent { ZillitTheme(darkTheme = dark) { StillsScreen(open, onEvent = {}) } }
                onNodeWithText("A001.jpg").assertExists()
                onNodeWithText("1 of 2").assertExists()
                // The gate: one row per member who needs an agent's say.
                onNodeWithText("✓ Approve").assertExists()
                onNodeWithText("✕ Kill").assertExists()
                onNodeWithText("\"eyes closed\"").assertExists()
                onNodeWithText("no agent yet").assertExists()
                // A face set aside is still drawn, so the crew can see what was
                // put aside; the button that sets one aside says the same words.
                // The faces, as the crew names them.
                onNodeWithText("Is this Ben Bold? (68% match)").assertExists()
                onAllNodesWithText("Not cast").assertCountEquals(2)
                onNodeWithText("Find in other photos").assertExists()
                onNodeWithText("ADD SOMEONE THE CAMERA MISSED").assertExists()
                // Production's own tools.
                onNodeWithText("Download original").assertExists()
                onNodeWithText("🗑 Delete photo").assertExists()
                // More than 100 faces is said, not hidden.
                onNodeWithText("More than 100 faces: only the first 100 were looked at.").assertExists()
                // The picture zooms, from its corner as well as by click and wheel.
                onNodeWithText("100%").assertExists()
                onNodeWithContentDescription("Zoom in").assertExists()
            }
        }
    }

    /**
     * The picture is left clear: no labelled box over every face (thirty
     * labels over a group shot hid the photo). Only the face whose row is
     * pointed at is outlined, and an outline carries no name.
     */
    @Test
    fun `the picture carries no face labels, pointed at or not`() {
        listOf(null, "f1").forEach { active ->
            runComposeUiTest {
                val open = state(StillsPage.Photos).copy(
                    lightbox = LightboxState(photoId = "p1", ids = listOf("p1"), photo = photo, loading = false, activeFace = active),
                )
                setContent { ZillitTheme(darkTheme = false) { StillsScreen(open, onEvent = {}) } }
                // Two faces nobody has named yet (one only suggested): each is
                // "unknown" once, in its row — the picture adds no label of its
                // own, and pointing at a row adds none either.
                onAllNodesWithText("unknown").assertCountEquals(2)
                // The face set aside: its row says so, the picture does not.
                onAllNodesWithText("not cast").assertCountEquals(1)
            }
        }
    }

    /**
     * The arrows sit at the two edges of the picture, not on top of each other.
     *
     * A tooltip wrapped around each one swallowed its `align` — BoxScope's
     * `align` only acts on a direct child — and both ended up stacked in the
     * middle of the photo. Positions, not existence, are the only thing that
     * catches it.
     */
    @Test
    fun `the lightbox's two arrows sit at opposite edges of the picture`() {
        runComposeUiTest {
            val open = state(StillsPage.Photos).copy(
                lightbox = LightboxState(photoId = "p1", ids = listOf("p0", "p1", "p2"), photo = photo, loading = false),
            )
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(open, onEvent = {}) } }
            val back = onNodeWithText("‹").fetchSemanticsNode().positionInRoot.x
            val forward = onNodeWithText("›").fetchSemanticsNode().positionInRoot.x
            assertTrue(forward - back > minArrowGap, "the arrows are $back and $forward — they should be at opposite edges")
        }
    }

    /**
     * The header keeps its pills and its ✕ beside the file name.
     *
     * The name carries `Modifier.weight(1f)`, and a tooltip wrapped around the
     * text swallowed it — RowScope's `weight` only acts on a direct child — so
     * the name took the whole header and pushed everything else out.
     */
    @Test
    fun `the lightbox header keeps its state pill and its close beside the name`() {
        runComposeUiTest {
            // A camera's real file name, which is what overflowed the header:
            // a short one fits either way and proves nothing.
            val long = photo.copy(originalName = "3E37B924-0116-4E6F-B994-4E9B2C7A5D10-IMG-0042.jpeg")
            val open = state(StillsPage.Photos).copy(
                lightbox = LightboxState(photoId = "p1", ids = listOf("p1"), photo = long, loading = false),
            )
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(open, onEvent = {}) } }
            // The gate below the header wears the same pill, so take the first.
            onAllNodesWithText("◷ awaiting").onFirst().assertIsDisplayed()
            // Only the header says this: the tiles behind are two-handers.
            onNodeWithText("group · 3").assertIsDisplayed()
            onAllNodesWithText("✕").onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun `the queue's lightbox pins the decision and leaves naming to the crew`() {
        runComposeUiTest {
            val open = state(StillsPage.Review).copy(
                lightbox = LightboxState(photoId = "p1", ids = listOf("p1"), run = true, photo = photo, loading = false),
            )
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(open, onEvent = {}) } }
            onNodeWithText("✕ Kill…").assertExists()
            onNodeWithText("← → move · A approve · K kill · + − zoom · Esc close").assertExists()
            // `.stk-rl-block h4` is upper-cased by the stylesheet.
            onNodeWithText("OTHER AGENTS").assertExists()
            // Naming a face is the crew's job, in the gallery.
            onAllNodesWithText("Find in other photos").assertCountEquals(0)
            onAllNodesWithText("ADD SOMEONE THE CAMERA MISSED").assertCountEquals(0)
        }
    }

    @Test
    fun `the reason box replaces the buttons rather than opening under them`() {
        runComposeUiTest {
            val asking = state(StillsPage.Review).copy(
                lightbox = LightboxState(
                    photoId = "p1",
                    ids = listOf("p1"),
                    run = true,
                    photo = photo.copy(approvals = listOf(ApprovalRow("m1", "Anna Bell", canDecide = true))),
                    loading = false,
                    asking = true,
                    note = "eyes closed",
                ),
            )
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(asking, onEvent = {}) } }
            onNodeWithText("Why are you killing it? The crew and the other agents will see this.").assertExists()
            onNodeWithText("⌘/Ctrl + Enter").assertExists()
            // Two "Kill" controls at once would read as two different actions.
            onAllNodesWithText("✕ Kill…").assertCountEquals(0)
        }
    }

    @Test
    fun `a photo still being worked on opens anyway, so production can retry it`() {
        runComposeUiTest {
            val working = state(StillsPage.Photos).copy(
                lightbox = LightboxState(
                    photoId = "p4",
                    ids = listOf("p4"),
                    photo = Photo(id = "p4", status = PhotoStatus.Processing),
                    loading = false,
                ),
            )
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(working, onEvent = {}) } }
            onNodeWithText("Process again").assertExists()
        }
    }

    @Test
    fun `"find this person" lists the faces that look like them, none ticked`() {
        runComposeUiTest {
            val finding = state(StillsPage.Photos).copy(
                lightbox = LightboxState(
                    photoId = "p1",
                    ids = listOf("p1"),
                    photo = photo,
                    loading = false,
                    similar = SimilarState(
                        faceId = "f3",
                        items = listOf(
                            com.zillit.desktop.feature.selectstills.domain.SimilarFace("p2", "f9", 88f),
                            com.zillit.desktop.feature.selectstills.domain.SimilarFace("p3", "f7", 71f),
                        ),
                    ),
                ),
            )
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(finding, onEvent = {}) } }
            onNodeWithText("Find this person in other photos").assertExists()
            onNodeWithText("0 of 2 selected").assertExists()
            onNodeWithText("Select all").assertExists()
        }
    }

    // -- the cast ----------------------------------------------------------------------

    @Test
    fun `the enrol page draws the card and the list, marking what needs attention`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { StillsScreen(state(StillsPage.Cast), onEvent = {}) } }
                onNodeWithText("Actor name").assertExists()
                onNodeWithText("Who decides which photos of this person may be cleared?").assertExists()
                // The form's field, and Anna's row — where it is behind an (i).
                onAllNodesWithText("Kill allowance").assertCountEquals(2)
                onNodeWithContentDescription("Show the kill allowance").assertExists()
                onNodeWithText("Enroll cast member").assertExists()
                onNodeWithText("This person has agreed to their face being used to find them in photos.").assertExists()
                onNodeWithText("Enrolled cast members (3)").assertExists()
                onNodeWithText("Anna Bell").assertExists()
                // Loudly: photos of them are cleared without anybody being asked.
                onNodeWithText("no approval needed").assertExists()
                // Needs approval, no agent: every photo they are in is held. A
                // part of the line of its own, so the line breaks between parts.
                onNodeWithText("no agent yet").assertExists()
                onNodeWithText("Suggest only").assertExists()
            }
        }
    }

    @Test
    fun `a member's card draws their agent, their allowance and the two dangerous rows`() {
        runComposeUiTest {
            val open = state(StillsPage.Cast).copy(
                memberDialog = MemberDialogState(
                    memberId = "m1",
                    member = members.first(),
                    loading = false,
                    name = "Anna Bell",
                    character = "Mira",
                    agent = "u9",
                    limits = mapOf("1" to "2"),
                ),
            )
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(open, onEvent = {}) } }
            onNodeWithText("1 profile photo").assertExists()
            onAllNodesWithText("Agent").onFirst().assertExists()
            onNodeWithText("Erase face data").assertExists()
            onNodeWithText("Remove cast member").assertExists()
            // What is already spent, so a cap is not set below it by accident.
            onNodeWithText("1 used").assertExists()
        }
    }

    /**
     * A headshot the service turned away can be let go. Before, a refusal that
     * could not be overruled had no button at all, and the notice stayed until
     * the card was closed.
     */
    @Test
    fun `every turned-away headshot can be removed, and the overrulable ones added anyway`() {
        runComposeUiTest {
            val open = state(StillsPage.Cast).copy(
                memberDialog = MemberDialogState(
                    memberId = "m1",
                    member = members.first(),
                    loading = false,
                    name = "Anna Bell",
                    results = listOf(
                        HeadshotOutcome(name = "side.jpg", path = "/h/side.jpg", ok = false, reason = "still_kills_headshot_matches_other", matchedName = "Ben Bold"),
                        HeadshotOutcome(name = "crowd.jpg", path = "/h/crowd.jpg", ok = false, reason = "still_kills_headshot_many_faces"),
                    ),
                ),
            )
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(open, onEvent = {}) } }
            onAllNodesWithText("Add anyway").assertCountEquals(1)
            onAllNodesWithText("Remove").assertCountEquals(2)
        }
    }

    @Test
    fun `the face-data notice stands in front of the upload and the enrolment`() {
        listOf(StillsPage.Upload, StillsPage.Cast).forEach { page ->
            runComposeUiTest {
                val unattested = state(page).let { it.copy(me = it.me.copy(settings = it.me.settings.copy(attested = false))) }
                setContent { ZillitTheme(darkTheme = false) { StillsScreen(unattested, onEvent = {}) } }
                onNodeWithText("Before you start: this tool stores face data").assertExists()
                onNodeWithText("I understand").assertExists()
            }
        }
    }

    @Test
    fun `a production on storage the tool cannot read is told so`() {
        runComposeUiTest {
            val unusable = state(StillsPage.Upload).let { it.copy(me = it.me.copy(storageSupported = false)) }
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(unusable, onEvent = {}) } }
            onNodeWithText("Select Stills is not available for this production.").assertExists()
            onNodeWithText("This production keeps its files in its own Box storage, which Select Stills does not support yet.").assertExists()
        }
    }

    // -- the upload --------------------------------------------------------------------

    @Test
    fun `the upload page draws the drop zone and what the service takes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { StillsScreen(state(StillsPage.Upload), onEvent = {}) } }
                onNodeWithText("Shoot / day label (optional, used for filtering)").assertExists()
                onNodeWithText("Drop photos here or click to choose — hundreds at once is fine").assertExists()
                onNodeWithText("JPG · up to 50 MB each · group and single photos").assertExists()
                onNodeWithText("or choose a folder").assertExists()
                onNodeWithText("open the full gallery").assertExists()
            }
        }
    }

    /**
     * Every file of the upload is listed from the moment it is chosen — not
     * only the two or three moving at that moment — what needs a person first.
     */
    @Test
    fun `the upload lists every file it was given, what needs a person first`() {
        runComposeUiTest {
            val queue = waitingQueue("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg", refused = "z.heic")
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(state(StillsPage.Upload), onEvent = {}, queue = queue) } }
            listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg", "z.heic").forEach { onNodeWithText(it).assertExists() }
            // Nothing moves in this test, so every file the queue took waits.
            onAllNodesWithText("Waiting").assertCountEquals(5)
            val refused = onNodeWithText("z.heic").fetchSemanticsNode().positionInRoot.y
            val first = onNodeWithText("a.jpg").fetchSemanticsNode().positionInRoot.y
            assertTrue(refused < first, "the file that needs a person is listed above the waiting ones")
        }
    }

    // -- a wide tool -------------------------------------------------------------------

    /**
     * On a tool at least 1100 wide the settings' three sections stand side by
     * side (the web, 8 October); below that they stack, as before. Positions,
     * not existence, are what tell the two apart.
     */
    @Test
    fun `settings stand side by side on a wide tool and stack on a narrow one`() {
        mapOf(1500.dp to true, 900.dp to false).forEach { (width, beside) ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = false) {
                        Box(Modifier.requiredSize(width, 1400.dp)) { StillsScreen(state(StillsPage.Settings), onEvent = {}) }
                    }
                }
                val scope = onNodeWithText("Who can browse the gallery").fetchSemanticsNode().positionInRoot
                val numbers = onNodeWithText("Name automatically at").fetchSemanticsNode().positionInRoot
                assertEquals(beside, numbers.x > scope.x + 200f, "at $width the matching numbers are at $numbers, the scope at $scope")
            }
        }
    }

    @Test
    fun `on a wide tool the cast list takes the room the form leaves`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    Box(Modifier.requiredSize(1600.dp, 1200.dp)) { StillsScreen(state(StillsPage.Cast), onEvent = {}) }
                }
            }
            // `.stk-member-list` is a grid at this width: two of the three side by side.
            val anna = onNodeWithText("Anna Bell").fetchSemanticsNode().positionInRoot
            val ben = onNodeWithText("Ben Bold").fetchSemanticsNode().positionInRoot
            assertEquals(anna.y, ben.y, 1f, "Anna at $anna and Ben at $ben should share a row")
        }
    }

    // -- settings ----------------------------------------------------------------------

    @Test
    fun `settings draw the scope, the default allowance and the matching numbers`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { StillsScreen(state(StillsPage.Settings), onEvent = {}) } }
                onNodeWithText("Who can browse the gallery").assertExists()
                onNodeWithText("Cleared photos only").assertExists()
                onNodeWithText("Everything").assertExists()
                onNodeWithText("Default kill allowance").assertExists()
                onNodeWithText("Name automatically at").assertExists()
                onNodeWithText("Lead over the next match").assertExists()
                onNodeWithText("Delete all face data").assertExists()
            }
        }
    }

    @Test
    fun `numbers that cannot be saved say why`() {
        runComposeUiTest {
            val bad = state(StillsPage.Settings).let { it.copy(settings = it.settings.copy(auto = "60", suggest = "90")) }
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(bad, onEvent = {}) } }
            onNodeWithText("Use numbers from 0 to 100, with “suggest” no higher than “name automatically”.").assertExists()
        }
    }

    @Test
    fun `only a project admin is offered the production's face-data delete`() {
        runComposeUiTest {
            val notAdmin = state(StillsPage.Settings).let { it.copy(me = it.me.copy(isAdmin = false)) }
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(notAdmin, onEvent = {}) } }
            onNodeWithText("Only a project admin can delete all face data.").assertExists()
        }
    }

    /**
     * A queue holding these files, none of which ever moves: its coroutines
     * run on a dispatcher nobody advances. [refused] is a file the queue turns
     * away before anything is sent.
     */
    private fun waitingQueue(vararg names: String, refused: String): StillsUploadQueue {
        val queue = StillsUploadQueue(
            repository = FakeStillsRepository(),
            uploader = NeverUploads,
            files = FakeStillsFiles(),
            openProject = { "proj-1" },
            newId = { "id-${names.size}-${System.nanoTime()}" },
            scope = CoroutineScope(StandardTestDispatcher()),
        )
        val picks = names.map { StillsPick(path = "/card/$it", name = it, size = 1000, type = "image/jpeg") } +
            StillsPick(path = "/card/$refused", name = refused, size = 1000, type = "image/heic")
        queue.add(picks, shootLabel = "", types = listOf("image/jpeg"), maxBytes = 50_000)
        return queue
    }

    private object NeverUploads : StillsUploader {
        override suspend fun put(url: String, headers: Map<String, String>, path: String, contentType: String, size: Long, onProgress: (Float) -> Unit) = PutOutcome.Ok
        override suspend fun putBytes(url: String, headers: Map<String, String>, bytes: ByteArray, contentType: String) = PutOutcome.Ok
    }
}
