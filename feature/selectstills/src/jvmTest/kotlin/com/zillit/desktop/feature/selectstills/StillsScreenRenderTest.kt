@file:Suppress("MaxLineLength", "LongParameterList", "LongMethod")
// One fixture carrying every state the pages draw from; splitting it would
// mean five near-copies that drift apart.

package com.zillit.desktop.feature.selectstills

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
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
import com.zillit.desktop.feature.selectstills.ui.StillsPage
import com.zillit.desktop.feature.selectstills.ui.StillsPerson
import com.zillit.desktop.feature.selectstills.ui.StillsScreen
import com.zillit.desktop.feature.selectstills.ui.StillsUiState
import kotlin.test.Test
import kotlin.test.assertTrue

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
        // Nobody has to approve: marked loudly, because photos of them go public unasked.
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
                onNodeWithText("Enroll member").assertExists()
                onNodeWithText("Photographer upload").assertExists()
                onNodeWithText("Publisher review").assertExists()
                onNodeWithText("Settings").assertExists()
                // The tool's own name sits on the left, in the accent.
                onNodeWithText("Select Stills").assertExists()
                // How many photos wait on this person, beside "Publisher review".
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
            listOf("Enroll member", "Photographer upload", "Publisher review").forEach {
                onNodeWithText(it).assertIsDisplayed()
            }
            // "Photos" and "Settings" are each on the bar and on their page, so
            // the bar's one is the first of the two.
            onAllNodesWithText("Photos").onFirst().assertIsDisplayed()
            onAllNodesWithText("Settings").onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun `a reader who can only view is offered no upload and no settings`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(state(StillsPage.Photos, canPost = false, scope = ViewerScope.Cleared), onEvent = {}) } }
            onAllNodesWithText("Photographer upload").assertCountEquals(0)
            onAllNodesWithText("Settings").assertCountEquals(0)
            // The bar's link and the page's own heading both say "Photos".
            onAllNodesWithText("Photos").assertCountEquals(2)
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
                onNodeWithText("✓ public").assertExists()
                onNodeWithText("✕ blocked").assertExists()
                onNodeWithText("2 unknown").assertExists()
                onNodeWithText("failed").assertExists()
            }
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
                onNodeWithText("Kept").assertExists()
                onNodeWithText("Discarded").assertExists()
                onNodeWithText("Everything").assertExists()
                onNodeWithText("Represents").assertExists()
                onNodeWithText("✓ Keep").assertExists()
                onNodeWithText("✕ Discard").assertExists()
                // The other agent's refusal, and what it means for this one.
                onNodeWithText("Ben Bold: discarded").assertExists()
                onNodeWithText("Already discarded by another agent, so this will not go public even if you keep it.").assertExists()
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
            // Once over the queue (what is left of each capped section), once on the card.
            onAllNodesWithText("3 people: no discards left, all 2 used").assertCountEquals(2)
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
            onAllNodesWithText("✓ Keep").assertCountEquals(0)
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
                onNodeWithText("✕ Reject").assertExists()
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
            onNodeWithText("✕ Discard…").assertExists()
            onNodeWithText("← → move · K keep · D discard · Esc close").assertExists()
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
            onNodeWithText("Why are you discarding it? The crew and the other agents will see this.").assertExists()
            onNodeWithText("⌘/Ctrl + Enter").assertExists()
            // Two "Discard" controls at once would read as two different actions.
            onAllNodesWithText("✕ Discard…").assertCountEquals(0)
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
                onNodeWithText("Who decides which photos of this person may go public?").assertExists()
                onNodeWithText("Discard allowance").assertExists()
                onNodeWithText("This person has agreed to their face being used to find them in photos.").assertExists()
                onNodeWithText("Enrolled members (3)").assertExists()
                onNodeWithText("Anna Bell").assertExists()
                // Loudly: photos of them go public without anybody being asked.
                onNodeWithText("no approval needed").assertExists()
                // Needs approval, no agent: every photo they are in is held.
                onNodeWithText(" · no agent yet").assertExists()
                onNodeWithText("Suggest only").assertExists()
            }
        }
    }

    @Test
    fun `a viewer reads the list and is offered no enrolment card`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { StillsScreen(state(StillsPage.Cast, canPost = false), onEvent = {}) } }
            onNodeWithText("Enrolled members").assertExists()
            onAllNodesWithText("Actor name").assertCountEquals(0)
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
            onAllNodesWithText("Agent / photo publisher", substring = true).assertCountEquals(2)
            onNodeWithText("Erase face data").assertExists()
            onNodeWithText("Remove member").assertExists()
            // What is already spent, so a cap is not set below it by accident.
            onNodeWithText("1 used").assertExists()
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

    // -- settings ----------------------------------------------------------------------

    @Test
    fun `settings draw the scope, the default allowance and the matching numbers`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { StillsScreen(state(StillsPage.Settings), onEvent = {}) } }
                onNodeWithText("Who can browse the gallery").assertExists()
                onNodeWithText("Public photos only").assertExists()
                onNodeWithText("Everything").assertExists()
                onNodeWithText("Default discard allowance").assertExists()
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
}
