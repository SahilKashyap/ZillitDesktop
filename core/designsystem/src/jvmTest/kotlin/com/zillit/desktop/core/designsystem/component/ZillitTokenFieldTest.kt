package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The token field, driven the way a person drives it: type, separate, remove.
 *
 * The state lives in the test, exactly as it lives in a caller — every change
 * arrives as the whole new value, and the test holds it.
 */
@OptIn(ExperimentalTestApi::class)
class ZillitTokenFieldTest {

    private class Held(var tokens: List<String>, var input: String)

    private fun androidx.compose.ui.test.ComposeUiTest.field(initial: List<String> = emptyList()): Held {
        val held = Held(initial, "")
        setContent {
            var tokens by androidx.compose.runtime.remember { mutableStateOf(held.tokens) }
            var input by androidx.compose.runtime.remember { mutableStateOf(held.input) }
            ZillitTheme(darkTheme = false) {
                Column(Modifier.width(400.dp)) {
                    ZillitTokenField(
                        tokens = tokens,
                        input = input,
                        onValueChange = { next, rest ->
                            tokens = next
                            input = rest
                            held.tokens = next
                            held.input = rest
                        },
                        placeholder = "To",
                        isTokenValid = { it.contains('@') },
                        collapsedLimit = 2,
                    )
                    // Somewhere for focus to go.
                    ZillitTextField(value = "", onValueChange = {}, placeholder = "Subject")
                }
            }
        }
        return held
    }

    private fun androidx.compose.ui.test.ComposeUiTest.input() = onAllNodes(hasSetTextAction())[0]

    @Test
    fun `typing a separator commits a chip and clears the input`() {
        runComposeUiTest {
            val held = field()

            input().performTextInput("a@b.com,")

            assertEquals(listOf("a@b.com"), held.tokens)
            assertEquals("", held.input)
            onNodeWithText("a@b.com").assertIsDisplayed()
        }
    }

    @Test
    fun `Enter commits and Backspace on an empty input removes the last chip`() {
        runComposeUiTest {
            val held = field()

            input().performTextInput("a@b.com")
            input().performKeyInput { pressKey(Key.Enter) }
            assertEquals(listOf("a@b.com"), held.tokens)

            input().performKeyInput { pressKey(Key.Backspace) }
            assertEquals(emptyList(), held.tokens)
        }
    }

    @Test
    fun `a pasted list commits everything before the last separator`() {
        runComposeUiTest {
            val held = field()

            input().performTextInput("a@b.com, c@d.com, e")

            assertEquals(listOf("a@b.com", "c@d.com"), held.tokens)
            assertEquals("e", held.input)
        }
    }

    @Test
    fun `the chip's cross removes it`() {
        runComposeUiTest {
            val held = field(listOf("a@b.com", "c@d.com"))

            onNodeWithContentDescription("Remove a@b.com").performClick()

            assertEquals(listOf("c@d.com"), held.tokens)
            onAllNodesWithText("a@b.com").assertCountEquals(0)
        }
    }

    @Test
    fun `a duplicate is not added twice`() {
        runComposeUiTest {
            val held = field(listOf("a@b.com"))

            input().performTextInput("A@B.COM,")

            assertEquals(listOf("a@b.com"), held.tokens)
        }
    }

    @Test
    fun `text left in the field is committed when focus leaves`() {
        runComposeUiTest {
            val held = field()

            input().performTextInput("a@b.com")
            onAllNodes(hasSetTextAction())[1].performClick()

            assertEquals(listOf("a@b.com"), held.tokens)
        }
    }

    @Test
    fun `an unfocused field folds its extra chips`() {
        runComposeUiTest {
            field(listOf("a@b.com", "c@d.com", "e@f.com", "g@h.com"))

            onNodeWithText("+2 more").assertIsDisplayed()
            onAllNodesWithText("g@h.com").assertCountEquals(0)

            onNodeWithText("+2 more").performClick()
            onNodeWithText("g@h.com").assertIsDisplayed()
        }
    }
}
