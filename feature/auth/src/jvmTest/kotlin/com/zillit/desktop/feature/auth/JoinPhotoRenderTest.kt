package com.zillit.desktop.feature.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.auth.domain.JoinDraft
import com.zillit.desktop.feature.auth.domain.JoinPhoto
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.ui.JoinEvent
import com.zillit.desktop.feature.auth.ui.JoinFlowState
import com.zillit.desktop.feature.auth.ui.JoinProductionDialog
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The photo control on the join form.
 *
 * The model tests prove the picture is stored and sent; this proves the button
 * that starts it is actually on the screen. A control the rules depend on but
 * nobody can reach passes every unit test and ships a form that cannot do the
 * thing it was changed to do.
 */
@OptIn(ExperimentalTestApi::class)
class JoinPhotoRenderTest {

    private val project = Project(
        id = "p1", name = "Feature Film", code = "986381", type = null, region = null,
    )

    private fun onDetails(
        canChoosePhoto: Boolean = true,
        photo: JoinPhoto? = null,
        isStoringPhoto: Boolean = false,
        photoError: String? = null,
    ) = JoinFlowState(
        codeText = "986381",
        project = project,
        draft = JoinDraft(firstName = "Peach", lastName = "Android"),
        canChoosePhoto = canChoosePhoto,
        photo = photo,
        isStoringPhoto = isStoringPhoto,
        photoError = photoError,
    )

    @Test
    fun `the details step offers a photo`() = runComposeUiTest {
        setContent {
            ZillitTheme { JoinProductionDialog(onDetails(), onEvent = {}, visible = true) }
        }

        onNodeWithText("Choose photo").assertExists()
        onNodeWithText("Add a photo — optional").assertExists()
        // Optional, and it must read that way — nobody should think the join
        // is blocked on finding a headshot.
        onNodeWithText("Remove").assertDoesNotExist()
    }

    @Test
    fun `choosing one is what the button asks for`() = runComposeUiTest {
        var fired: JoinEvent? = null
        setContent {
            ZillitTheme {
                JoinProductionDialog(onDetails(), onEvent = { fired = it }, visible = true)
            }
        }

        onNodeWithText("Choose photo").performClick()

        assertEquals(JoinEvent.ChoosePhoto, fired)
    }

    @Test
    fun `a stored photo can be changed or removed`() = runComposeUiTest {
        val stored = JoinPhoto("m", "t", "b", "r")
        setContent {
            ZillitTheme {
                JoinProductionDialog(onDetails(photo = stored), onEvent = {}, visible = true)
            }
        }

        onNodeWithText("Photo added").assertExists()
        onNodeWithText("Change").assertExists()
        onNodeWithText("Remove").assertExists()
    }

    @Test
    fun `the upload says it is happening`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                JoinProductionDialog(onDetails(isStoringPhoto = true), onEvent = {}, visible = true)
            }
        }

        onNodeWithText("Saving your photo…").assertExists()
    }

    @Test
    fun `a storage failure is shown without blocking the request`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                JoinProductionDialog(
                    onDetails(photoError = "No storage."),
                    onEvent = {},
                    visible = true,
                )
            }
        }

        onNodeWithText("No storage.").assertExists()
        // The join itself is still sendable.
        onNodeWithText("Send request").assertExists()
    }

    @Test
    fun `a host with no storage shows no photo control at all`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                JoinProductionDialog(onDetails(canChoosePhoto = false), onEvent = {}, visible = true)
            }
        }

        onNodeWithText("Choose photo").assertDoesNotExist()
        onNodeWithText("Add a photo — optional").assertDoesNotExist()
        onNodeWithText("Send request").assertExists()
    }
}
