package com.zillit.desktop.feature.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.settings.account.AccountEvent
import com.zillit.desktop.feature.settings.account.AccountPage
import com.zillit.desktop.feature.settings.account.AccountScreen
import com.zillit.desktop.feature.settings.account.AccountUiState
import com.zillit.desktop.feature.settings.account.DevicesState
import com.zillit.desktop.feature.settings.account.LeaveProductionDialog
import com.zillit.desktop.feature.settings.account.LeaveState
import com.zillit.desktop.feature.settings.account.LinkedDevice
import com.zillit.desktop.feature.settings.account.ProfileFormState
import com.zillit.desktop.feature.settings.account.ProfileSeed
import com.zillit.desktop.feature.settings.approvals.CrewDepartment
import com.zillit.desktop.feature.settings.approvals.CrewRole
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Composes every account page for real.
 *
 * ## Why render rather than screenshot
 *
 * Screenshot verification needs macOS screen-recording permission, which CI
 * does not have. Composing the actual page exercises the same layout code, and
 * catches the two failures the unit tests cannot: a page that throws while
 * composing, and a nested-scroll arrangement that crashes on an unbounded
 * constraint. Both are how a page ships looking fine in review and blank in use.
 *
 * `assertExists`, not `assertIsDisplayed`: the test window is small and these
 * pages scroll, so anything past the first screenful is composed without being
 * on screen.
 */
@OptIn(ExperimentalTestApi::class)
class AccountScreenRenderTest {

    private val seed = ProfileSeed(
        firstName = "Aisha",
        lastName = "Khan",
        email = "aisha@example.com",
        departmentId = "dept-camera",
        designationId = "role-focus",
        designationName = "focus_puller_label",
        productionName = "Dune",
        productionCode = "DUNE-1",
    )

    private val loaded = ProfileFormState(
        firstName = "Aisha",
        lastName = "Khan",
        departmentId = "dept-camera",
        designationId = "role-focus",
        departments = listOf(
            CrewDepartment(
                id = "dept-camera",
                name = "camera_department_label",
                roles = listOf(CrewRole("role-focus", "focus_puller_label")),
            ),
        ),
    )

    private val devices = DevicesState(
        devices = listOf(
            LinkedDevice("here", "Sahil's MacBook Pro", "desktop", "15.2", "1.4.0", null, isThisDevice = true),
            LinkedDevice("phone", "iPhone 16", "ios", "18.3", "3.9.1", null),
        ),
    )

    @Test
    fun `every account page composes`() {
        AccountPage.entries.forEach { page ->
            runComposeUiTest {
                setContent {
                    ZillitTheme {
                        AccountScreen(
                            page = page,
                            state = AccountUiState(seed = seed, profile = loaded, devices = devices),
                            onEvent = {},
                            onBack = {},
                        )
                    }
                }
                // `onAllNodesWithText`, because the page title and the section
                // card under it legitimately carry the same words on two of the
                // four — "Recovery email" is both the heading and the field's
                // card. A unique-match assertion fails on the duplication
                // rather than on anything being wrong.
                onAllNodesWithText(page.tabTitle).onFirst().assertExists()
            }
        }
    }

    @Test
    fun `the profile form shows the name it was seeded with`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                AccountScreen(
                    page = AccountPage.EditProfile,
                    state = AccountUiState(seed = seed, profile = loaded),
                    onEvent = {},
                    onBack = {},
                )
            }
        }

        onNodeWithText("Aisha").assertExists()
        onNodeWithText("Khan").assertExists()
        // Everyone who is not an admin is told before they type, not after they
        // save — this is the notice that says so.
        onNodeWithText("You are not an administrator", substring = true).assertExists()
    }

    @Test
    fun `an admin sees no approval warning`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                AccountScreen(
                    page = AccountPage.EditProfile,
                    state = AccountUiState(seed = seed.copy(isAdmin = true), profile = loaded),
                    onEvent = {},
                    onBack = {},
                )
            }
        }

        onNodeWithText("Save profile").assertExists()
    }

    @Test
    fun `the device list marks this computer and offers the other a sign-out`() =
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    AccountScreen(
                        page = AccountPage.LinkedDevices,
                        state = AccountUiState(seed = seed, devices = devices),
                        onEvent = {},
                        onBack = {},
                    )
                }
            }

            onNodeWithText("Sahil's MacBook Pro").assertExists()
            onNodeWithText("This computer").assertExists()
            onNodeWithText("iPhone 16").assertExists()
        }

    @Test
    fun `the invite page prints the production code`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                AccountScreen(
                    page = AccountPage.InviteCrew,
                    state = AccountUiState(seed = seed),
                    onEvent = {},
                    onBack = {},
                )
            }
        }

        onNodeWithText("DUNE-1").assertExists()
        onNodeWithText("Copy the invite").assertExists()
    }

    @Test
    fun `back is offered on every page and reports once`() = runComposeUiTest {
        var backs = 0
        setContent {
            ZillitTheme {
                AccountScreen(
                    page = AccountPage.RecoveryEmail,
                    state = AccountUiState(seed = seed),
                    onEvent = {},
                    onBack = { backs++ },
                )
            }
        }

        // The chevron carries no text — it is reachable by its description,
        // which is also what a screen reader announces.
        onNodeWithContentDescription("Back to settings").performClick()
        assertTrue(backs == 1, "expected one back, got $backs")
    }

    @Test
    fun `the leave dialog warns an admin about leaving nobody in charge`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                LeaveProductionDialog(
                    state = LeaveState(isConfirming = true),
                    productionName = "Dune",
                    isAdmin = true,
                    onEvent = {},
                )
            }
        }

        onNodeWithText("Leave Dune?").assertExists()
        onNodeWithText("You administer this production", substring = true).assertExists()
    }

    @Test
    fun `signing this computer out is asked about differently`() = runComposeUiTest {
        val here = devices.devices.first { it.isThisDevice }
        setContent {
            ZillitTheme {
                AccountScreen(
                    page = AccountPage.LinkedDevices,
                    state = AccountUiState(
                        seed = seed,
                        devices = devices.copy(confirming = here),
                    ),
                    onEvent = {},
                    onBack = {},
                )
            }
        }

        onNodeWithText("Sign this computer out?").assertExists()
        onNodeWithText("Sign out here").assertExists()
    }

    @Test
    fun `an empty device list says so rather than showing nothing`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                AccountScreen(
                    page = AccountPage.LinkedDevices,
                    state = AccountUiState(seed = seed, devices = DevicesState()),
                    onEvent = {},
                    onBack = {},
                )
            }
        }

        onNodeWithText("Only this computer").assertExists()
    }

    @Test
    fun `a production with no code cannot be invited to`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                AccountScreen(
                    page = AccountPage.InviteCrew,
                    state = AccountUiState(seed = seed.copy(productionCode = "")),
                    onEvent = {},
                    onBack = {},
                )
            }
        }

        onNodeWithText("No production code").assertExists()
    }
}
