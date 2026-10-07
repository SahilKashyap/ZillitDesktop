package com.zillit.desktop.feature.accounthub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SectionEdit
import com.zillit.desktop.feature.accounthub.ui.SetupState
import com.zillit.desktop.feature.accounthub.ui.pages.ProductionCompaniesPage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Admin Settings → Production Setup, composed.
 *
 * The web hosts the Companies section alone on this page: the console's
 * sidebar, bank register and accounting sections do not belong to it, and its
 * back arrow goes to Admin Settings.
 */
@OptIn(ExperimentalTestApi::class)
class ProductionCompaniesPageRenderTest {

    private val admin = AccountHubViewer(
        userId = "u1",
        isAdmin = true,
        canView = true,
        canPost = true,
        ready = true,
    )

    private fun state(viewer: AccountHubViewer = admin, loaded: Boolean = true) = AccountHubUiState(
        viewer = viewer,
        setup = SetupState(
            loaded = loaded,
            loading = !loaded,
            companies = SectionEdit(
                listOf(
                    Company(id = "co-1", name = "Zillit Films Ltd", country = "United Kingdom", bankIds = listOf("b1")),
                ),
            ),
            banks = listOf(
                BankAccount(
                    id = "b1",
                    name = "Barclays",
                    entityId = "co-1",
                    sortCode = "204891",
                    accountNumber = "20481234",
                ),
            ),
        ),
    )

    @Test
    fun `shows the companies section alone under the web's header`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { ProductionCompaniesPage(state(), onEvent = {}, onBack = {}) }
            }
            onNodeWithText("Production Setup").assertIsDisplayed()
            onNodeWithText("Companies / Entities").assertIsDisplayed()
            onNodeWithText("Zillit Films Ltd").assertIsDisplayed()
            // None of the console's other sections, and no sidebar.
            onAllNodesWithText("Bank Accounts").assertCountEquals(0)
            onAllNodesWithText("Account Hub").assertCountEquals(0)
        }
    }

    @Test
    fun `the back arrow leaves for admin settings`() {
        runComposeUiTest {
            var backs = 0
            setContent {
                ZillitTheme(darkTheme = false) { ProductionCompaniesPage(state(), onEvent = {}, onBack = { backs++ }) }
            }
            onNodeWithContentDescription("Back to admin settings").performClick()
            assertEquals(1, backs)
        }
    }

    @Test
    fun `adding a company asks the editor to open on a blank one`() {
        runComposeUiTest {
            val events = mutableListOf<AccountHubEvent>()
            setContent {
                ZillitTheme(darkTheme = false) { ProductionCompaniesPage(state(), onEvent = events::add, onBack = {}) }
            }
            onNodeWithText("Add company").performClick()
            assertEquals(listOf<AccountHubEvent>(AccountHubEvent.EditCompany(null)), events)
        }
    }

    @Test
    fun `a page still loading shows a loader, not the companies`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    ProductionCompaniesPage(state(loaded = false), onEvent = {}, onBack = {})
                }
            }
            onNodeWithText("Loading Companies…").assertIsDisplayed()
            onAllNodesWithText("Companies / Entities").assertCountEquals(0)
        }
    }
}
