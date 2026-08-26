package com.zillit.desktop.core.locationpicker

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Composes the real field: the map affordance appears only when a picker is
 * provided, picking fills the text *and* reports coordinates, and typing works
 * either way — an address someone knows by heart must never need the map.
 */
@OptIn(ExperimentalTestApi::class)
class ZillitLocationFieldRenderTest {

    /** Stands in for the desktop's Chromium picker, which no test can open. */
    private class FakeLocationPicker(private val answer: PickedLocation?) : LocationPicker {
        var seenInitial: PickedLocation? = null
        var seenTitle: String? = null
        var calls = 0

        override suspend fun pick(initial: PickedLocation?, title: String): PickedLocation? {
            calls++
            seenInitial = initial
            seenTitle = title
            return answer
        }
    }

    private val aria = PickedLocation(
        name = "Aria Hotel",
        address = "1 Aria Road, Mumbai 400001, India",
        lat = 19.075984,
        lng = 72.877656,
    )

    @Test
    fun `with a picker, the map button opens it and the pick fills text and coordinates`() = runComposeUiTest {
        val picker = FakeLocationPicker(aria)
        val saved = PickedLocation("Base camp", "Old Wharf Road", 1.0, 2.0)
        var reported: PickedLocation? = null
        var text by mutableStateOf("Old Wharf Road")

        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides picker) {
                    ZillitLocationField(
                        text = text,
                        onTextChange = { text = it },
                        onPicked = { reported = it },
                        label = "Pickup address",
                        initial = saved,
                    )
                }
            }
        }

        onNodeWithText("Pickup address").assertIsDisplayed()
        onNodeWithText("Pick on map").assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 5_000) { reported != null }

        assertEquals(aria, assertNotNull(reported))
        assertEquals(aria.address, text, "the pick did not fill the field")
        // The dialog is seeded with the saved place and titled for the field.
        assertEquals(saved, picker.seenInitial)
        assertEquals("Pickup address", picker.seenTitle)
    }

    @Test
    fun `typing still works while a picker is provided`() = runComposeUiTest {
        val picker = FakeLocationPicker(aria)
        var text by mutableStateOf("Old Wharf Road")

        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides picker) {
                    ZillitLocationField(
                        text = text,
                        onTextChange = { text = it },
                        onPicked = {},
                        label = "Pickup address",
                    )
                }
            }
        }

        onNodeWithText("Old Wharf Road").performTextReplacement("Stage 4, Film City")

        assertEquals("Stage 4, Film City", text)
        assertEquals(0, picker.calls, "typing must not open the map")
    }

    @Test
    fun `without a picker the field is plain text and offers no map`() = runComposeUiTest {
        var text by mutableStateOf("Stage 4, Film City")

        setContent {
            ZillitTheme {
                ZillitLocationField(
                    text = text,
                    onTextChange = { text = it },
                    onPicked = {},
                    label = "Venue",
                    helperText = "Where the unit is called to",
                )
            }
        }

        onNodeWithText("Venue").assertIsDisplayed()
        onNodeWithText("Where the unit is called to").assertIsDisplayed()
        onNodeWithText("Stage 4, Film City").performTextReplacement("Stage 9")
        assertEquals("Stage 9", text)
        assertEquals(
            0,
            onAllNodesWithText("Pick on map").fetchSemanticsNodes().size,
            "the map button must be absent with no picker provided",
        )
    }
}
