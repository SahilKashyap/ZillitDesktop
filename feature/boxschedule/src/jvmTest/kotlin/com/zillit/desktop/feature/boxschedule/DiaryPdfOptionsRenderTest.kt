package com.zillit.desktop.feature.boxschedule

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.PdfSheet
import com.zillit.desktop.feature.boxschedule.ui.pages.PdfOptionsDialog
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The diary PDF dialog: one prompt with the layout and the Personal Notes
 * choice, defaulting to the file the server always sent, and a publish
 * button only for someone with posting rights on Document Distribution.
 */
@OptIn(ExperimentalTestApi::class)
class DiaryPdfOptionsRenderTest {

    @Test
    fun `the dialog opens on Calendar with Personal Notes and offers Save`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        setContent {
            ZillitTheme { PdfOptionsDialog(sheet = PdfSheet(), onEvent = events::add) }
        }

        onNodeWithText("Diary PDF").assertExists()
        onNodeWithText("Calendar").assertExists()
        onNodeWithText("With Personal Notes").assertExists()
        onNodeWithText("Publish to Document Distribution").assertDoesNotExist()
        onNodeWithText("Save PDF").performClick()
        assertEquals(listOf<BoxScheduleEvent>(BoxScheduleEvent.SavePdf), events)
    }

    @Test
    fun `posting rights on the library add the publish destination`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        setContent {
            ZillitTheme { PdfOptionsDialog(sheet = PdfSheet(canPublish = true), onEvent = events::add) }
        }

        onNodeWithText("Publish to Document Distribution").performClick()
        assertEquals(listOf<BoxScheduleEvent>(BoxScheduleEvent.PublishPdf), events)
    }
}
