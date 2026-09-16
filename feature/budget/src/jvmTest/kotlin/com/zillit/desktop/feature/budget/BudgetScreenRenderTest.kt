package com.zillit.desktop.feature.budget

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.budget.domain.BudgetActivity
import com.zillit.desktop.feature.budget.domain.BudgetActivityRow
import com.zillit.desktop.feature.budget.domain.BudgetChatEntry
import com.zillit.desktop.feature.budget.domain.BudgetDepartment
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetMode
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetViewer
import com.zillit.desktop.feature.budget.ui.BudgetActivityDialog
import com.zillit.desktop.feature.budget.ui.BudgetContext
import com.zillit.desktop.feature.budget.ui.BudgetDepartmentDrawer
import com.zillit.desktop.feature.budget.ui.BudgetEvent
import com.zillit.desktop.feature.budget.ui.BudgetMembersDialog
import com.zillit.desktop.feature.budget.ui.BudgetPerson
import com.zillit.desktop.feature.budget.ui.BudgetScreen
import com.zillit.desktop.feature.budget.ui.BudgetUiState
import com.zillit.desktop.feature.budget.ui.BudgetUnread
import com.zillit.desktop.feature.budget.ui.BudgetUploadDraft
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Composes the real screen in every state it can reach, light and dark.
 *
 * These catch what unit tests cannot: a layout that throws on infinite
 * constraints, a menu that crashes on open, or a pane that composes at zero
 * size.
 */
@OptIn(ExperimentalTestApi::class)
class BudgetScreenRenderTest {

    /** The everyday case: the full budget, a version open, two conversations. */
    @Test
    fun `the full budget draws in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = dark) {
                        BudgetScreen(state = versions(), onEvent = {})
                    }
                }
                onNodeWithText("Budget (Full)").assertExists()
                onNodeWithText("Budget (Full) -SEP 05, 2026").assertExists()
                onNodeWithText("v2.pdf").assertExists()
                onNodeWithText("Ravi Menon").assertExists()
                onNodeWithText("Camera crew").assertExists()
                onNodeWithText("Pick a conversation").assertExists()
            }
        }
    }

    /** The version picker opens with its rows — the DropdownMenu-in-a-lazy-list trap. */
    @Test
    fun `the version picker opens and lists every version`() {
        runComposeUiTest {
            val events = mutableListOf<BudgetEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(state = versions().copy(versionMenuOpen = true), onEvent = events::add)
                }
            }
            onNodeWithText("Budget (Full) -SEP 04, 2026").assertExists()
            onNodeWithText("Budget (Full) -SEP 04, 2026").performClick()
            assertEquals(BudgetEvent.SelectVersion("v1"), events.last())
        }
    }

    /** The department directory: rows with their badges, and the placeholder beside it. */
    @Test
    fun `the department directory draws its rows`() {
        runComposeUiTest {
            val events = mutableListOf<BudgetEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(state = directory(), onEvent = events::add)
                }
            }
            onNodeWithText("Budget (Department)").assertExists()
            onNodeWithText("Camera").assertExists()
            onNodeWithText("Art").assertExists()
            onNodeWithText("Pick a department").assertExists()
            onNodeWithText("Camera").performClick()
            assertEquals(BudgetEvent.OpenDepartment("cam"), events.last())
        }
    }

    /** A department open: the breadcrumb leads back. */
    @Test
    fun `an open department wears its breadcrumb`() {
        runComposeUiTest {
            val events = mutableListOf<BudgetEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(
                        state = versions().copy(
                            mode = BudgetMode.Department,
                            viewer = BudgetViewer(canViewDepartment = true, canPostDepartment = true, resolved = true),
                            openDepartment = BudgetDepartment("cam", "Camera"),
                        ),
                        onEvent = events::add,
                    )
                }
            }
            onNodeWithText("Department List").assertExists()
            onNodeWithText("Department List").performClick()
            assertEquals(BudgetEvent.BackToDirectory, events.last())
        }
    }

    /** Every dialog composes over the screen. */
    @Test
    fun `the dialogs draw`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(
                        state = versions().copy(
                            upload = BudgetUploadDraft(fileName = "New.pdf", bytes = ByteArray(3)),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Upload budget").assertExists()
            onNodeWithText("New.pdf").assertExists()
        }
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(
                        state = versions().copy(
                            members = BudgetMembersDialog(
                                kind = BudgetMembersDialog.Kind.Group,
                                loading = false,
                                candidates = listOf(BudgetPerson("u9", "Anita Rao", "Producer")),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Group name").assertExists()
            onNodeWithText("Anita Rao").assertExists()
        }
    }

    /** The admin's count sheet and the department drawer. */
    @Test
    fun `the sheets draw`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(
                        state = versions().copy(
                            activity = BudgetActivityDialog(
                                activity = BudgetActivity.View,
                                loading = false,
                                rows = listOf(BudgetActivityRow("u2", viewCount = 4)),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("View count : 4").assertExists()
        }
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(
                        state = directory().copy(
                            drawer = BudgetDepartmentDrawer(departments = listOf(BudgetDepartment("snd", "Sound"))),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Sound").assertExists()
        }
    }

    /** Nothing uploaded yet, and no rights: the screen says so instead of an empty list. */
    @Test
    fun `empty and refused states draw`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(state = versions().copy(documents = emptyList(), selectedId = null), onEvent = {})
                }
            }
            onNodeWithText("No budget uploaded yet").assertExists()
        }
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(
                        state = BudgetUiState(viewer = BudgetViewer(resolved = true)),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("No access to Budget (Full)").assertExists()
        }
    }

    private fun versions() = BudgetUiState(
        mode = BudgetMode.Main,
        viewer = BudgetViewer(
            canViewMain = true, canPostMain = true, canDownloadMain = true, isAdmin = true, resolved = true,
        ),
        context = BudgetContext(userId = "me", departmentId = "cam"),
        documents = listOf(
            document("v2", "Budget (Full) -SEP 05, 2026", updated = 20),
            document("v1", "Budget (Full) -SEP 04, 2026", updated = 10),
        ),
        selectedId = "v2",
        chats = listOf(
            BudgetChatEntry.Person("u2", "Ravi Menon", designation = "Gaffer", isAdmin = true),
            BudgetChatEntry.Group("r1", "Camera crew", memberIds = listOf("u2", "u3")),
        ),
        unread = BudgetUnread(chats = mapOf("v2" to mapOf("u2" to 2))),
    )

    private fun directory() = BudgetUiState(
        mode = BudgetMode.Department,
        viewer = BudgetViewer(canViewDepartment = true, canPostDepartment = true, canViewMain = true, resolved = true),
        showingDirectory = true,
        directory = listOf(BudgetDepartment("cam", "Camera"), BudgetDepartment("art", "Art")),
        unread = BudgetUnread(departments = mapOf("cam" to 3)),
    )

    private fun document(id: String, title: String, updated: Long) = BudgetDocument(
        id = id,
        type = BudgetType.Main,
        title = title,
        file = BudgetFile(media = "k/$id.pdf", name = "$id.pdf", sizeBytes = 120_000),
        uploadedById = "u1",
        uploadedByName = "Ravi Menon",
        createdMillis = 1_788_609_600_000L,
        updatedMillis = updated,
    )
}
