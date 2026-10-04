package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
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
 * The shared select list, driven as a person drives it: open, read the
 * tiles and the footer, search, arrow down and press ↵, toggle several.
 */
@OptIn(ExperimentalTestApi::class)
class ZillitSelectPopupTest {

    private val departments = listOf(
        "Direction", "Producers", "Production", "Writer", "Post-Production", "Accounts", "Art", "Camera",
    )

    @Test
    fun `initials are the label's first two letters or digits`() {
        assertEquals("DI", optionInitials("Direction"))
        assertEquals("PO", optionInitials("Post-Production"))
        assertEquals("2N", optionInitials("2nd Unit"))
        assertEquals("", optionInitials("—"))
    }

    @Test
    fun `a long select opens with tiles, a search line and the count`() = runComposeUiTest {
        var picked = departments.first()
        setContent {
            var value by remember { mutableStateOf(picked) }
            ZillitTheme(darkTheme = false) {
                ZillitSelect(
                    value = value,
                    options = departments,
                    onSelect = { value = it; picked = it },
                    label = { it },
                    modifier = Modifier.width(300.dp),
                )
            }
        }
        onNodeWithText("Direction").performClick()
        onNodeWithText("PO").assertExists()
        onNodeWithText("8 options").assertExists()
        onNode(hasSetTextAction()).performTextInput("prod")
        // Producers, Production, Post-Production.
        onNodeWithText("3 options").assertExists()
        onNodeWithText("Writer").assertDoesNotExist()
        onNode(hasSetTextAction()).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertEquals("Production", picked)
        // Closed again: the list's own rows are gone, only the field's label stays.
        onAllNodesWithText("Production").assertCountEquals(1)
    }

    @Test
    fun `a short select has no search line but still answers the keys`() = runComposeUiTest {
        var picked = "Low"
        setContent {
            var value by remember { mutableStateOf(picked) }
            ZillitTheme(darkTheme = false) {
                Column {
                    ZillitSelect(
                        value = value,
                        options = listOf("Low", "Medium", "High"),
                        onSelect = { value = it; picked = it },
                        label = { it },
                    )
                }
            }
        }
        onNodeWithText("Low").performClick()
        onNode(hasSetTextAction()).assertDoesNotExist()
        onNodeWithText("Medium").performClick()
        waitForIdle()
        assertEquals("Medium", picked)
    }

    @Test
    fun `a multi select stays open while rows are toggled`() = runComposeUiTest {
        var chosen = emptyList<String>()
        setContent {
            var selected by remember { mutableStateOf(chosen) }
            ZillitTheme(darkTheme = false) {
                ZillitMultiSelect(
                    selected = selected,
                    options = departments,
                    label = { it },
                    onChange = { selected = it; chosen = it },
                    placeholder = "Pick departments",
                    modifier = Modifier.width(320.dp),
                )
            }
        }
        onNodeWithText("Pick departments").performClick()
        onNodeWithText("Writer").performClick()
        onNodeWithText("Producers").performClick()
        waitForIdle()
        assertEquals(listOf("Writer", "Producers"), chosen)
        onNodeWithText("8 options · 2 selected").assertExists()
    }

    @Test
    fun `a search that matches nothing offers to create the typed text`() = runComposeUiTest {
        var created: String? = null
        setContent {
            ZillitTheme(darkTheme = false) {
                ZillitSearchSelect(
                    value = null,
                    options = departments,
                    onSelect = {},
                    label = { it },
                    placeholder = "Select department…",
                    onCreate = { created = it },
                    modifier = Modifier.width(300.dp),
                )
            }
        }
        onNodeWithText("Select department…").performClick()
        onNode(hasSetTextAction()).performTextInput("Stunts")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("Stunts", created)
    }

    @Test
    fun `sections head their groups and the keys skip a disabled row`() = runComposeUiTest {
        var picked = "UK VAT 20%"
        val taxes = listOf("UK VAT 20%", "UK VAT 5%", "US Sales", "Custom")
        setContent {
            var value by remember { mutableStateOf(picked) }
            ZillitTheme(darkTheme = false) {
                ZillitSelect(
                    value = value,
                    options = taxes,
                    onSelect = { value = it; picked = it },
                    label = { it },
                    section = {
                        when {
                            it.startsWith("UK") -> "United Kingdom"
                            it.startsWith("US") -> "United States"
                            else -> null
                        }
                    },
                    isEnabled = { it != "UK VAT 5%" },
                    modifier = Modifier.width(300.dp),
                )
            }
        }
        onNodeWithText("UK VAT 20%").performClick()
        onNodeWithText("UNITED KINGDOM").assertExists()
        onNodeWithText("UNITED STATES").assertExists()
        // The disabled row ignores the pointer…
        onNodeWithText("UK VAT 5%").performClick()
        waitForIdle()
        assertEquals("UK VAT 20%", picked)
        // …and ↓ from the picked row jumps over it.
        onAllNodes(androidx.compose.ui.test.isRoot())[1].performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertEquals("US Sales", picked)
    }

    @Test
    fun `the clear cross empties a search select and a pinned action runs`() = runComposeUiTest {
        var value: String? = "Writer"
        var added = false
        setContent {
            var held by remember { mutableStateOf(value) }
            ZillitTheme(darkTheme = false) {
                ZillitSearchSelect(
                    value = held,
                    options = departments,
                    onSelect = { held = it; value = it },
                    label = { it },
                    placeholder = "Select department…",
                    onClear = { held = null; value = null },
                    pinnedAction = ZillitOptionAction("+ New department") { added = true },
                    modifier = Modifier.width(300.dp),
                )
            }
        }
        onNodeWithContentDescription("Clear Selection").performClick()
        waitForIdle()
        assertEquals(null, value)
        onNodeWithText("Select department…").performClick()
        onNodeWithText("+ New department").performClick()
        waitForIdle()
        assertEquals(true, added)
    }
}
