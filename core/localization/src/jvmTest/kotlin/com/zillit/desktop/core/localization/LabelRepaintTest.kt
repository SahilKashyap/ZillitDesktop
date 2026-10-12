package com.zillit.desktop.core.localization

import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * What a language change does to a server-named label already on screen.
 *
 * The regression this guards is the one people reported as "language
 * switching is not smooth": every `str(S.key)` repainted at once and every
 * label beside it — tool names, units, departments — kept the previous
 * language until its screen was closed and reopened, because `.localised()`
 * sampled a flow nothing was collecting.
 */
@OptIn(ExperimentalTestApi::class)
class LabelRepaintTest {

    /** Answers from a script; the key is deliberately not its own wording. */
    private class FakeSource(private val answers: Map<Pair<LabelKind, String>, Map<String, String>>) : LabelSource {
        override suspend fun fetch(kind: LabelKind, language: String): ZillitResult<Map<String, String>> =
            ZillitResult.Success(answers[kind to language].orEmpty())
    }

    @AfterTest
    fun tearDown() {
        Labels.reset()
    }

    @Test
    fun `a label on screen follows the language, without reopening the screen`() = runComposeUiTest {
        val store = LabelStore(
            FakeSource(
                mapOf(
                    (LabelKind.Labels to "en") to mapOf("cs_tool" to "Call Sheet"),
                    (LabelKind.Labels to "fr") to mapOf("cs_tool" to "Feuille de service"),
                ),
            ),
            cache = null,
        )
        Labels.install(store.dictionary)
        runBlocking { store.refresh("en") }

        setContent { BasicText("cs_tool".localised()) }
        onNodeWithText("Call Sheet").assertExists()

        runBlocking { store.refresh("fr") }
        waitForIdle()

        onNodeWithText("Feuille de service").assertExists()
        onNodeWithText("Call Sheet").assertDoesNotExist()
    }
}
