package com.zillit.desktop.feature.email

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.ui.EmailScreen
import com.zillit.desktop.feature.email.ui.EmailUiState
import com.zillit.desktop.feature.email.ui.LIST_PANE_TAG
import com.zillit.desktop.feature.email.ui.PANE_TAG
import com.zillit.desktop.feature.email.ui.SPLITTER_TAG
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The mailbox as it is actually laid out with a message open.
 *
 * Composed rather than reasoned about: the split is a width clamped to what
 * the panes are given, and the arithmetic being right says nothing about the
 * nav strip, the sidebar, the splitter's own width, or a drag reaching the
 * handle at all.
 */
@OptIn(ExperimentalTestApi::class)
class MailboxSplitRenderTest {

    private val state = EmailUiState(
        folders = listOf(EmailFolder(name = "INBOX", isSystem = true)),
        selectedFolderName = "INBOX",
        messages = listOf(
            EmailSummary(
                id = "m1",
                threadId = "t1",
                subject = "Call sheet — day 14",
                from = "AD <ad@zillit.com>",
                folderName = "INBOX",
            ),
            EmailSummary(
                id = "m2",
                threadId = "t2",
                subject = OTHER_SUBJECT,
                from = "Costume <costume@zillit.com>",
                snippet = "Fittings moved to Thursday.",
                folderName = "INBOX",
            ),
        ),
        openRowId = "m1",
    ).regrouped()

    @Test
    fun `the listing opens at the web's default width and the message takes the rest`() = mailboxTest {
        setMailbox()

        // The web's list opens at its 280 minimum; the reading pane has the
        // rest of the 1200 after the nav strip, the sidebar and two splitters.
        assertNear(280.dp, listWidth(), "listing")
        assertNear(MAILBOX_WIDTH - NAV_STRIP - SIDEBAR - 280.dp - SPLITTER * 2, readingWidth(), "message")
    }

    @Test
    fun `dragging the bar widens the listing and narrows the message`() = mailboxTest {
        setMailbox()
        val message = readingWidth()

        onNodeWithTag(SPLITTER_TAG).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(200f, 0f))
            release()
        }

        assertNear(480.dp, listWidth(), "listing after the drag")
        assertTrue(readingWidth() < message, "the message pane should have given the width up")
    }

    @Test
    fun `the bar stops rather than squeezing the listing away`() = mailboxTest {
        setMailbox()

        onNodeWithTag(SPLITTER_TAG).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(-2000f, 0f))
            release()
        }

        // Clamped at the listing's minimum — not at zero, and not off-screen.
        assertNear(280.dp, listWidth(), "listing dragged hard left")
    }

    /**
     * A mailbox in a window of a stated size.
     *
     * The size is the point of the test, and the default surface is 1024x768 —
     * narrow enough that the listing lands on its minimum and the split under
     * test never happens.
     */
    private fun mailboxTest(block: suspend ComposeUiTest.() -> Unit) =
        runSkikoComposeUiTest(size = Size(MAILBOX_WIDTH.value, MAILBOX_HEIGHT.value)) { block() }

    @Test
    fun `a listing this narrow still says what each message is about`() = mailboxTest {
        setMailbox()

        // The regression this pane width caused: on one line, the sender column
        // and the time left the subject about 8pt and it vanished. It now has a
        // line of its own — inside the listing, not spilling under the splitter.
        val listing = onNodeWithTag(LIST_PANE_TAG).getUnclippedBoundsInRoot()
        val subject = onNodeWithText(OTHER_SUBJECT).getUnclippedBoundsInRoot()

        assertTrue(subject.width > MIN_READABLE_SUBJECT, "the subject is only ${subject.width} wide")
        assertTrue(subject.right <= listing.right, "the subject spills out of the listing")
    }

    private fun ComposeUiTest.setMailbox() {
        setContent {
            ZillitTheme(darkTheme = false, animateThemeChange = false) {
                EmailScreen(state = state, onEvent = {})
            }
        }
    }

    private fun ComposeUiTest.listWidth(): Dp = onNodeWithTag(LIST_PANE_TAG).getUnclippedBoundsInRoot().width

    private fun ComposeUiTest.readingWidth(): Dp = onNodeWithTag(PANE_TAG).getUnclippedBoundsInRoot().width

    private fun assertNear(expected: Dp, actual: Dp, what: String) {
        assertTrue(
            abs((expected - actual).value) <= TOLERANCE.value,
            "$what should be about $expected, was $actual",
        )
    }
}

private val MAILBOX_WIDTH = 1200.dp
private val MAILBOX_HEIGHT = 800.dp

/** The web's fixed nav strip and the sidebar's opening width. */
private val NAV_STRIP = 48.dp
private val SIDEBAR = 200.dp

/** `ZillitPaneSplitter`'s grab bar. */
private val SPLITTER = 8.dp

/** Rounding between pixels and points; anything larger is a layout change. */
private val TOLERANCE = 2.dp

/** A subject only the listing carries — the open message's own is on both panes. */
private const val OTHER_SUBJECT = "Costume fitting times"

/** Narrower than this and the subject is an ellipsis, which is the bug. */
private val MIN_READABLE_SUBJECT = 120.dp
