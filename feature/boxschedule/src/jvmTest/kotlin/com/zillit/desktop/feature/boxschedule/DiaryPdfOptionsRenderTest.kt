package com.zillit.desktop.feature.boxschedule

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfLayout
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfOptions
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.PanelEvent
import com.zillit.desktop.feature.boxschedule.ui.PdfDestination
import com.zillit.desktop.feature.boxschedule.ui.PdfSheet
import com.zillit.desktop.feature.boxschedule.ui.pages.PdfOptionsDialog
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "PDF options" — the web's prompt before a print or a publish: the layout,
 * and whether the viewer's own Personal Notes go in.
 */
@OptIn(ExperimentalTestApi::class)
class DiaryPdfOptionsRenderTest {

    @Test
    fun `the prompt offers both layouts and Personal Notes, and Submit sends what was chosen`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        setContent { ZillitTheme { PdfOptionsDialog(PdfSheet(PdfDestination.Print), events::add) } }
        waitForIdle()

        onNodeWithText("PDF options").assertExists()
        onNodeWithText("Calendar").assertExists()
        onNodeWithText("Include my Personal Notes").performClick()
        onNodeWithText("List").performClick()
        onNodeWithText("Submit").performClick()

        assertEquals(
            listOf<BoxScheduleEvent>(
                PanelEvent.SetPdfPersonalNotes(false),
                PanelEvent.SetPdfLayout(DiaryPdfLayout.List),
                PanelEvent.SubmitPdf,
            ),
            events,
        )
    }

    @Test
    fun `a publish names its destination, and a busy submit cannot be cancelled`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        setContent {
            ZillitTheme {
                PdfOptionsDialog(PdfSheet(PdfDestination.Publish, DiaryPdfOptions(), busy = true), events::add)
            }
        }
        waitForIdle()

        onNodeWithText("Publish to Document Distribution").assertExists()
        onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<BoxScheduleEvent>(), events)
    }
}
