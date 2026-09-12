package com.zillit.desktop.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.textColumn
import kotlin.test.Test

/**
 * A short table inside a page that already scrolls still draws its rows.
 *
 * The case `virtualised = false` exists for: a dashboard's "recent cards", a
 * form's list of what you have already filed. Its rows had stopped appearing —
 * the section drew its heading and then nothing, which reads as a panel that
 * failed to load rather than as a bug. Four modules use this combination.
 */
@OptIn(ExperimentalTestApi::class)
class DataTableInScrollingPageTest {

    private data class Row(val id: String, val name: String)

    @Test
    fun `rows appear in a non-virtualised table inside a scrolling column`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ZillitDataTable(
                        rows = listOf(Row("1", "Ada Lovelace"), Row("2", "Grace Hopper")),
                        columns = listOf(textColumn("Holder", ColumnWidth.Weight(1f)) { it.name }),
                        key = { it.id },
                        virtualised = false,
                    )
                }
            }
        }
        onNodeWithText("Ada Lovelace").assertIsDisplayed()
        onNodeWithText("Grace Hopper").assertIsDisplayed()
    }

    /** And the empty state, which was equally invisible. */
    @Test
    fun `an empty non-virtualised table still says why it is empty`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ZillitDataTable(
                        rows = emptyList<Row>(),
                        columns = listOf(textColumn("Holder", ColumnWidth.Weight(1f)) { it.name }),
                        emptyTitle = "No cards issued",
                        virtualised = false,
                    )
                }
            }
        }
        onNodeWithText("No cards issued").assertIsDisplayed()
    }
}
