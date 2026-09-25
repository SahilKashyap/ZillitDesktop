package com.zillit.desktop.feature.auth

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.auth.domain.AuthRepository
import com.zillit.desktop.feature.auth.domain.PresetRepository
import com.zillit.desktop.feature.auth.domain.ProductionLanguage
import com.zillit.desktop.feature.auth.domain.ProductionType
import com.zillit.desktop.feature.auth.domain.ProjectRepository
import com.zillit.desktop.feature.auth.ui.CreateProductionDialog
import com.zillit.desktop.feature.auth.ui.CreateProductionEvent
import com.zillit.desktop.feature.auth.ui.CreateProductionViewModel
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Filling in the Start Project form, end to end, through the real view model.
 *
 * Reported as: "I filled all details and tapped Continue", and every field
 * typed before a dropdown came back empty. The dropdowns submitted the draft
 * as it was when the dialog opened, so picking a type wiped the names and
 * picking a language then wiped the type — while the fields filled after the
 * last dropdown survived, which is what made it look like the user had
 * skipped half the form.
 *
 * Driven by clicks and typing rather than by events, because the bug lived in
 * which callback the UI was holding, and no event-level test can see that.
 */
@OptIn(ExperimentalTestApi::class)
class CreateProductionInputTest {

    /** A repository the flow must not touch before Continue. */
    private inline fun <reified T> untouched(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("unexpected call: ${method.name}") } as T

    private val presets = object : PresetRepository {
        override suspend fun productionTypes() =
            ZillitResult.Success(listOf(ProductionType("feature", "Feature Film")))
        override suspend fun languages() =
            ZillitResult.Success(listOf(ProductionLanguage("en", "English"), ProductionLanguage("fr", "French")))
    }

    @Test
    fun `picking from the dropdowns keeps everything already filled in`() = runComposeUiTest {
        val viewModel = CreateProductionViewModel(untouched<ProjectRepository>(), presets, untouched<AuthRepository>())
        setContent {
            val state by viewModel.state.collectAsState()
            ZillitTheme { CreateProductionDialog(state, viewModel::onEvent, onDismiss = {}, visible = true) }
        }
        viewModel.onEvent(CreateProductionEvent.Opened)
        mainClock.advanceTimeBy(2_000)
        waitForIdle()

        // In the order a person fills it: names, then the two dropdowns, then terms.
        val fields = onAllNodes(hasSetTextAction())
        fields[0].performTextInput("Sahil")
        fields[1].performTextInput("Kashyap")
        fields[2].performTextInput("My Film")
        onNodeWithText("Choose a type", useUnmergedTree = true).performClick()
        onAllNodesWithText("Feature Film", useUnmergedTree = true).onFirst().performClick()
        onNodeWithText("Choose a language", useUnmergedTree = true).performClick()
        onAllNodesWithText("English", useUnmergedTree = true).onFirst().performClick()
        onNodeWithText("I accept", substring = true, useUnmergedTree = true).performClick()
        waitForIdle()

        val draft = viewModel.state.value.draft
        assertEquals("Sahil", draft.firstName, "picking a type must not wipe the first name")
        assertEquals("Kashyap", draft.lastName, "picking a type must not wipe the last name")
        assertEquals("My Film", draft.productionName, "picking a type must not wipe the project name")
        assertEquals("feature", draft.typeId, "picking a language must not wipe the type")
        assertEquals("en", draft.languageCode)
        assertTrue(draft.agreedToTerms)
    }
}
