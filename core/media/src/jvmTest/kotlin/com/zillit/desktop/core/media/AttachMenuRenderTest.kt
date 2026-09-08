package com.zillit.desktop.core.media

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The attach sheet on screen.
 *
 * A menu that fails to open is invisible to a model test, and so is one that
 * opens for a single kind — the phones' sheet of one collapses to a plain
 * button, and a menu with one row would be a question with no answer.
 */
@OptIn(ExperimentalTestApi::class)
class AttachMenuRenderTest {

    @Test
    fun `the button opens the phones' four kinds`() = runComposeUiTest {
        setContent { ZillitTheme { AttachMenu(kinds = ALL_ATTACHMENT_KINDS, onPick = {}) } }

        onNodeWithContentDescription("Attach").performClick()
        waitForIdle()

        listOf("Photo", "Video", "Document", "Audio").forEach { onNodeWithText(it).assertExists() }
    }

    @Test
    fun `picking a row reports its kind and closes the sheet`() = runComposeUiTest {
        var picked: PreviewKind? = null
        setContent { ZillitTheme { AttachMenu(kinds = ALL_ATTACHMENT_KINDS, onPick = { picked = it }) } }

        onNodeWithContentDescription("Attach").performClick()
        waitForIdle()
        onNodeWithText("Video").performClick()
        waitForIdle()

        assertEquals(PreviewKind.Video, picked)
        onNodeWithText("Photo").assertDoesNotExist()
    }

    @Test
    fun `a sheet of one collapses to a button that picks straight away`() = runComposeUiTest {
        // The call sheet: documents only, and no menu to open.
        var picked: PreviewKind? = null
        setContent {
            ZillitTheme { AttachMenu(kinds = listOf(PreviewKind.Document), onPick = { picked = it }) }
        }

        onNodeWithContentDescription("Attach a document").performClick()
        waitForIdle()

        assertEquals(PreviewKind.Document, picked)
        onNodeWithText("Document").assertDoesNotExist()
    }

    @Test
    fun `nothing to offer means nothing to press`() = runComposeUiTest {
        var picked: PreviewKind? = null
        setContent { ZillitTheme { AttachMenu(kinds = emptyList(), onPick = { picked = it }) } }

        onNodeWithContentDescription("Attach").performClick()
        waitForIdle()

        assertNull(picked)
    }
}
