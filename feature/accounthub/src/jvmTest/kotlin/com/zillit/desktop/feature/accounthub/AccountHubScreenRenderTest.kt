package com.zillit.desktop.feature.accounthub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalRule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.ApprovalTier
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubNavigation
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubScreen
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.EmbeddedTool
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.accounthub.ui.ApprovalsState
import com.zillit.desktop.feature.accounthub.ui.ChartState
import com.zillit.desktop.feature.accounthub.domain.DealCondition
import com.zillit.desktop.feature.accounthub.domain.PayrollBureau
import com.zillit.desktop.feature.accounthub.ui.SetupTab
import com.zillit.desktop.feature.accounthub.ui.SectionEdit
import com.zillit.desktop.feature.accounthub.ui.SetupState
import com.zillit.desktop.feature.accounthub.ui.VendorFormPage
import com.zillit.desktop.feature.accounthub.ui.VendorsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composes the real console on every screen.
 *
 * ## Why render rather than screenshot
 *
 * Screenshot verification needs macOS screen-recording permission, which CI
 * does not have. Composing the actual screen exercises the same layout code and
 * catches the two failures unit tests cannot: a page that throws while
 * composing, and a virtualised table inside a scrolling column — which is
 * measured against an infinite constraint and throws outright rather than
 * degrading. Both look fine in review and blank in use.
 *
 * This module is where that matters most: Production Setup scrolls and the
 * other three hold tables, so the two arrangements sit side by side behind one
 * sidebar.
 */
@OptIn(ExperimentalTestApi::class)
class AccountHubScreenRenderTest {

    private val accountant = AccountHubViewer(
        userId = "u1",
        isAccountant = true,
        canView = true,
        canPost = true,
        canDownload = true,
        ready = true,
        viewableTools = setOf(
            AccountHubViewer.TOOL_IDENTIFIER,
            "purchase_order_tool",
            "payroll_tool",
        ),
    )

    private val readOnly = accountant.copy(isAccountant = false, isAdmin = false)

    @Suppress("LongMethod") // One fixture covering every screen; splitting it hides the set.
    private fun state(area: HubArea, viewer: AccountHubViewer = accountant) = AccountHubUiState(
        viewer = viewer,
        sections = HubNavigation.visibleTo(viewer),
        area = area,
        setup = SetupState(
            companies = SectionEdit(
                listOf(
                    Company(
                        id = "co-1",
                        name = "Zillit Films Ltd",
                        country = "United Kingdom",
                        bankIds = listOf("bank-1"),
                    ),
                ),
            ),
            banks = listOf(
                BankAccount(
                    id = "bank-1",
                    name = "Barclays",
                    entityId = "co-1",
                    sortCode = "204891",
                    accountNumber = "20481234",
                    currencyCode = "GBP",
                ),
            ),
            currencies = SectionEdit(
                CurrencySettings(
                    currencies = listOf(
                        ProjectCurrency("GBP", "Pound Sterling", "£"),
                        ProjectCurrency("USD", "US Dollar", "$"),
                    ),
                    defaultCode = "GBP",
                ),
            ),
            taxTypes = SectionEdit(
                listOf(
                    TaxType(
                        type = "vat",
                        identifier = "GB_standard",
                        label = "Standard 20%",
                        value = "20",
                        isRecoverable = true,
                    ),
                ),
            ),
            assetTags = SectionEdit(listOf("Camera", "Lighting")),
            currencyCatalogue = listOf(ProjectCurrency("EUR", "Euro", "€")),
        ),
        chart = ChartState(
            accounts = listOf(
                CoaAccount(
                    id = "h1",
                    code = "1000",
                    name = "Production",
                    lineType = CoaLineType.Header,
                    headId = "h1",
                ),
                CoaAccount(
                    id = "s1",
                    code = "1100",
                    name = "Crew",
                    lineType = CoaLineType.Section,
                    headId = "h1",
                    sectionId = "s1",
                ),
            ),
            expanded = setOf("h1"),
        ),
        vendors = VendorsState(
            rows = listOf(
                Vendor(id = "v1", name = "Panavision", email = "hire@panavision.example", verified = true),
                Vendor(id = "v2", name = "Local Caterers", verified = false),
            ),
        ),
        approvals = ApprovalsState(
            configs = listOf(
                ApprovalConfig(
                    id = "cfg-1",
                    scope = ApprovalScope.All,
                    tiers = listOf(ApprovalTier(1, listOf(ApprovalRule("user", listOf("u1"))))),
                ),
            ),
        ),
    )

    /**
     * Every screen composes, driven off the enum.
     *
     * Off the enum rather than a hand-written list, so an area added later is
     * covered without anyone remembering to add it here.
     */
    @Test
    fun `every hub area composes`() {
        assertTrue(HubArea.entries.isNotEmpty())
        HubArea.entries.forEach { area ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = false) {
                        AccountHubScreen(state = state(area), onEvent = {})
                    }
                }
                // The sidebar is constant across screens, so its presence proves
                // the frame and the screen under it both composed. This
                // particular label because it is the only one that appears
                // *only* in the sidebar — every nav row's text is also its own
                // page's title, and the section headings collide with the page
                // eyebrows ("Configuration", "Setup"). Uppercase because
                // `ZillitSectionLabel` renders it that way.
                onAllNodesWithText("PAYROLL MANAGEMENT").onFirst().assertIsDisplayed()
                // And the open screen's own title, which does appear twice —
                // once in the sidebar, once as the page header. The sidebar row
                // is the clickable one; the last rows sit below the fold of a
                // sidebar that scrolls, so it is scrolled to rather than assumed.
                onAllNodes(hasText(area.label) and hasClickAction()).onFirst().performScrollTo().assertIsDisplayed()
            }
        }
    }

    @Test
    fun `every hub area composes in dark mode too`() {
        HubArea.entries.forEach { area ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = true) {
                        AccountHubScreen(state = state(area), onEvent = {})
                    }
                }
                onAllNodesWithText("PAYROLL MANAGEMENT").onFirst().assertIsDisplayed()
            }
        }
    }

    /**
     * Production Setup scrolls; the other three hold tables.
     *
     * Composed here as its own case because it is the arrangement that throws:
     * a virtualised table inside this page's scrolling column would take the
     * window down rather than degrade.
     */
    @Test
    fun `production setup renders its sections without nesting a table in the scroll`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(state = state(HubArea.ProductionSetup), onEvent = {})
                }
            }
            onNodeWithText("Companies / Entities").assertIsDisplayed()
            onNodeWithText("Bank Accounts").assertIsDisplayed()
            // Said out loud on the one section that has no section-level save,
            // so its absence does not read as a missing control.
            onNodeWithText("Each account saves on its own — there is no section-level save here.")
                .assertIsDisplayed()
        }
    }

    /**
     * The Deal Memo tab's two newest sections.
     *
     * Both shipped with a complete data layer and no screen, so the rows are
     * asserted rather than only the headings — a section card that draws its
     * title and none of its content would pass a heading-only check.
     */
    @Test
    fun `the deal memo tab renders its conditions and bureaux`() {
        val dealTab = state(HubArea.ProductionSetup).let { base ->
            base.copy(
                setup = base.setup.copy(
                    tab = SetupTab.DealMemo,
                    dealConditions = SectionEdit(
                        listOf(DealCondition("c1", 1, "Overtime is paid after ten hours.")),
                    ),
                    payrollBureaus = SectionEdit(listOf(PayrollBureau("b1", "Sargent-Disc"))),
                ),
            )
        }

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { AccountHubScreen(state = dealTab, onEvent = {}) }
            }
            // Composed rather than displayed: both sections sit below the
            // fold of the test window, and what is being proved here is that
            // they are built at all — they were absent entirely until now.
            onNodeWithText("Standard Deal Conditions").assertExists()
            onNodeWithText("Overtime is paid after ten hours.").assertExists()
            onNodeWithText("Payroll Bureau").assertExists()
            onNodeWithText("Sargent-Disc").assertExists()
        }
    }

    @Test
    fun `an empty deal memo tab says so rather than drawing bare cards`() {
        val empty = state(HubArea.ProductionSetup).let { base ->
            base.copy(setup = base.setup.copy(tab = SetupTab.DealMemo))
        }

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { AccountHubScreen(state = empty, onEvent = {}) }
            }
            onNodeWithText("No standard conditions yet.").assertExists()
            onNodeWithText("No payroll bureaux yet.").assertExists()
        }
    }

    /**
     * The stale-holder rule, on screen.
     *
     * The bank stores "Old Name Ltd" and its company is now "Zillit Films Ltd";
     * the row must show the live name. This is the one place the rule is
     * visible, and a unit test on `holderName` cannot prove the row calls it.
     */
    @Test
    fun `a bank row shows its company's current name, not the stored snapshot`() {
        val stale = state(HubArea.ProductionSetup).let { base ->
            base.copy(
                setup = base.setup.copy(
                    banks = base.setup.banks.map { it.copy(accountHolderName = "Old Name Ltd") },
                ),
            )
        }
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(state = stale, onEvent = {})
                }
            }
            onNodeWithText("Zillit Films Ltd · 20-48-91 · •••• 1234 · GBP").assertIsDisplayed()
        }
    }

    /** A section with unsaved edits offers a save; a clean page stays quiet. */
    @Test
    fun `a dirty section shows its save and a clean one does not`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(state = state(HubArea.ProductionSetup), onEvent = {})
                }
            }
            onNodeWithText("Save changes").assertDoesNotExist()
        }

        // Companies, because it is the first card. A section further down is
        // legitimately below the fold of a page that scrolls, and asserting its
        // button is *displayed* would be testing the viewport, not the rule.
        val dirty = state(HubArea.ProductionSetup).let { base ->
            base.copy(
                setup = base.setup.copy(
                    companies = base.setup.companies.edit(
                        base.setup.companies.saved + Company(id = "co-2", name = "Second Entity Ltd"),
                    ),
                ),
            )
        }
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(state = dirty, onEvent = {})
                }
            }
            // The save lives on the section itself, as on the web — no page-level
            // banner naming the dirty card.
            onNodeWithText("Save changes").assertIsDisplayed()
            onNodeWithText("Unsaved changes in Companies.").assertDoesNotExist()
        }
    }

    @Test
    fun `a non-accountant sees the configuration but is told they cannot change it`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(
                        state = state(HubArea.ProductionSetup, readOnly),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText(
                "You can see this configuration but not change it — edits are the " +
                    "accounts department's.",
            ).assertIsDisplayed()
            onNodeWithText("Add company").assertDoesNotExist()
        }
    }

    @Test
    fun `a blocked viewer sees one explanation rather than an empty console`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(
                        state = state(HubArea.ProductionSetup, accountant.copy(canView = false)),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("No access to the Account Hub").assertIsDisplayed()
        }
    }

    /**
     * A department user reaching the console has no hub screens at all.
     *
     * Null area is a real state, not a loading one. Their first tool has
     * already been opened in its own window by then
     * (`HubNavigation.landingTool`), so the body says where it went — the web
     * puts the same person straight into Purchase Orders.
     */
    @Test
    fun `a viewer with no hub screens is pointed at their tools`() {
        val departmentUser = AccountHubViewer(
            userId = "u2",
            isAccountant = false,
            canView = true,
            ready = true,
            viewableTools = setOf("purchase_order_tool"),
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(
                        state = AccountHubUiState(
                            viewer = departmentUser,
                            sections = HubNavigation.visibleTo(departmentUser),
                            area = HubNavigation.landing(departmentUser),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Your tools are open").assertIsDisplayed()
            onNodeWithText("Purchase Orders").assertIsDisplayed()
        }
    }

    /**
     * A sidebar row that points at another tool hands off rather than navigating.
     *
     * The distinction is the shape of this module, and a row that quietly did
     * nothing would look identical to one that worked.
     */
    @Test
    fun `selecting a hosted tool asks the host to open it`() {
        var handedOff: String? = null
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(
                        state = state(HubArea.ProductionSetup),
                        onEvent = { event ->
                            if (event is AccountHubEvent.OpenTool) handedOff = event.item.id
                        },
                    )
                }
            }
            onNodeWithText("Purchase Orders").performClick()
        }
        assertEquals("purchase-orders", handedOff)
    }

    /**
     * With a host that can embed, a tool row renders the tool inside the
     * console — the web's nested routes — and the hub's own area stays behind it.
     */
    @Test
    fun `a hosted tool renders inside the console when the host embeds it`() {
        val embedded = state(HubArea.ProductionSetup).copy(
            embedded = EmbeddedTool("/film-tools/purchase-orders", "Purchase Orders"),
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(
                        state = embedded,
                        onEvent = {},
                        embed = { tool -> ZillitText(text = "Embedded ${tool.title}") },
                    )
                }
            }
            onNodeWithText("Embedded Purchase Orders").assertIsDisplayed()
            onNodeWithText("Companies / Entities").assertDoesNotExist()
        }
    }

    @Test
    fun `selecting a hub area navigates within the console`() {
        var opened: HubArea? = null
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(
                        state = state(HubArea.ProductionSetup),
                        onEvent = { event ->
                            if (event is AccountHubEvent.Open) opened = event.area
                        },
                    )
                }
            }
            onNodeWithText("Vendors").performClick()
        }
        assertEquals(HubArea.Vendors, opened)
    }

    /** Verification is derived from a status string, and this is where it is believed. */
    @Test
    fun `the vendor register distinguishes verified from not`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(state = state(HubArea.Vendors), onEvent = {})
                }
            }
            // The pill on the row, not the "Verified" tab above it.
            onAllNodesWithText("Verified").onFirst().assertIsDisplayed()
            // "Non-Verified" is both the tab and the row's pill, as on the web
            // (ZL-20611: one literal wherever the status is named).
            val nonVerified = onAllNodesWithText("Non-Verified")
            nonVerified.assertCountEquals(2)
            nonVerified[1].assertIsDisplayed()
        }
    }

    /**
     * The vendor form is a page of its own, as on the web, with its save in
     * the top bar. `assertIsDisplayed` is the point of the test — a clipped
     * button still *exists*.
     */
    @Test
    fun `a vendor form keeps its save button on screen`() {
        val withForm = state(HubArea.Vendors).let { base ->
            base.copy(
                vendors = base.vendors.copy(
                    page = VendorFormPage(draft = NewVendor(name = "Grip Co", email = "hire@grip.example")),
                ),
            )
        }
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(state = withForm, onEvent = {})
                }
            }
            onNodeWithText("Create Vendor").assertIsDisplayed()
            onNodeWithText("Vendor Details").assertIsDisplayed()
            // The bank card sits below the fold of a page that scrolls; it exists, and
            // asserting it is *displayed* would be testing the viewport.
            onNodeWithText("Bank Details").assertExists()
        }
    }

    @Test
    fun `the chart renders its tree and names an unconfigured chain`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(state = state(HubArea.ChartOfAccounts), onEvent = {})
                }
            }
            onNodeWithText("1000").assertIsDisplayed()
            // A top-level group's name is drawn in capitals, as the web's tree draws it.
            onNodeWithText("PRODUCTION").assertIsDisplayed()
        }

        val noChain = state(HubArea.Approvers).let { base ->
            base.copy(approvals = base.approvals.copy(configs = emptyList()))
        }
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(state = noChain, onEvent = {})
                }
            }
            // Consequence, not just absence: the web's own warning.
            onNodeWithText(
                "No default levels set. Departments without custom configs will have no approval flow.",
            ).assertIsDisplayed()
        }
    }
}
