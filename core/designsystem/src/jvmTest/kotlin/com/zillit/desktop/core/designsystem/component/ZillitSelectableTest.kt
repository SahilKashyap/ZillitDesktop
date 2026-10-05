package com.zillit.desktop.core.designsystem.component

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The probe is what lets a surface's own "Copy" take a partial highlight
 * rather than the whole message, and it reads the selection through a
 * stand-in text menu — a seam a Compose upgrade could quietly close.
 */
@OptIn(ExperimentalTestApi::class)
class ZillitSelectableTest {

    @Test
    fun `the probe reports the highlighted word and nothing before it`() = runComposeUiTest {
        lateinit var probe: TextSelectionProbe
        setContent {
            ZillitTheme(darkTheme = false) {
                probe = rememberTextSelectionProbe()
                ZillitSelectable(probe = probe) { ZillitText("Rolling") }
            }
        }

        assertEquals("", probe.selectedText)
        assertEquals("Rolling at 8", probe.selectedOr("Rolling at 8"))

        onNodeWithText("Rolling").performMouseInput { doubleClick(center) }
        waitForIdle()

        assertEquals("Rolling", probe.selectedText)
        assertEquals("Rolling", probe.selectedOr("Rolling at 8"))
    }

    @Test
    fun `a tag or link inside selectable words still opens on a click`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            ZillitTheme(darkTheme = false) {
                ZillitSelectable {
                    ZillitText(
                        buildAnnotatedString {
                            withLink(LinkAnnotation.Clickable("mention:u1") { opened += "u1" }) { append("@Aisha") }
                        },
                    )
                }
            }
        }

        onNodeWithText("@Aisha").performMouseInput { click(center) }
        waitForIdle()

        assertEquals(listOf("u1"), opened)
    }
}
