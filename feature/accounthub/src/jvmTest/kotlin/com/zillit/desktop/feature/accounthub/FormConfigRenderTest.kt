package com.zillit.desktop.feature.accounthub

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalRule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.ApprovalTier
import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.ApprovalBuilder
import com.zillit.desktop.feature.accounthub.ui.ApprovalsState
import com.zillit.desktop.feature.accounthub.ui.BuilderOrigin
import com.zillit.desktop.feature.accounthub.ui.DiscardIntent
import com.zillit.desktop.feature.accounthub.ui.FieldFocus
import com.zillit.desktop.feature.accounthub.ui.FormConfigState
import com.zillit.desktop.feature.accounthub.ui.NewFieldDraft
import com.zillit.desktop.feature.accounthub.ui.ScopeModalState
import com.zillit.desktop.feature.accounthub.ui.SectionComposer
import com.zillit.desktop.feature.accounthub.ui.pages.FormConfigPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composes the form editor in each state, light and dark.
 *
 * Both themes because a colour defined in one and not the other is invisible
 * until somebody switches; the pickers and the rearrange drag driven rather
 * than merely composed, because opening a menu and moving a row are where a
 * layout throws.
 */
@OptIn(ExperimentalTestApi::class)
class FormConfigRenderTest {

    private val template = FormTemplate(
        listOf(
            FormSection(
                key = "header",
                label = "Header",
                order = 1,
                systemDefault = true,
                fields = listOf(
                    FormField(
                        label = "vendor",
                        name = "Vendor",
                        type = "select",
                        order = 1,
                        required = true,
                        systemDefault = true,
                        selectionType = "vendor",
                    ),
                    FormField(
                        label = "notes",
                        name = "Notes",
                        type = "textarea",
                        order = 2,
                        systemDefault = true,
                        hidden = true,
                    ),
                ),
            ),
            FormSection(
                key = "line_items",
                label = "Line Items",
                order = 2,
                systemDefault = true,
                fields = listOf(FormField(label = "amount", name = "Amount", type = "number", order = 1)),
            ),
            FormSection(
                key = "extras",
                label = "Extras",
                order = 3,
                systemDefault = false,
                fields = listOf(FormField(label = "budget_code", name = "Budget Code", order = 1)),
            ),
            FormSection(
                key = FormTemplate.TERMS_SECTION,
                label = "Terms of Engagement",
                order = 4,
                systemDefault = true,
            ),
        ),
    )

    private val accountant = AccountHubViewer(isAccountant = true, canPost = true, ready = true)

    private fun state(config: FormConfigState, approvals: ApprovalsState = ApprovalsState()) = AccountHubUiState(
        viewer = accountant,
        formConfig = config,
        approvals = approvals,
        departmentList = listOf(
            HubDepartment("dept-camera", "Camera"),
            HubDepartment("dept-accounts", "Accounts", identifier = "department_accounts"),
        ),
    )

    private val saved = FormConfigState(template = template, saved = template)

    @Test
    fun `the preview composes in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { FormConfigPage(state(saved)) {} } }
                onNodeWithText("Forms Configuration").assertExists()
                onNodeWithText("Modules").assertExists()
                onNodeWithText("Default Form Configuration").assertExists()
                // Section eyebrows are drawn uppercase, as the web's CSS does.
                onNodeWithText("HEADER").assertExists()
                onNodeWithText("LINE ITEMS").assertExists()
                // An empty terms section is not configurable at all.
                onNodeWithText("TERMS OF ENGAGEMENT").assertDoesNotExist()
                // A hidden field is not previewed.
                onAllNodesWithText("NOTES", substring = true).assertCountEquals(0)
                onNodeWithText("Set Approver Level").assertExists()
            }
        }
    }

    /** Line items are a table: its column headers carry no "(optional)", as on the web. */
    @Test
    fun `line items preview as a table`() {
        runComposeUiTest {
            setContent { ZillitTheme { FormConfigPage(state(saved)) {} } }
            onNode(hasTextExactly("AMOUNT")).assertExists()
            onNodeWithText("VENDOR *").assertExists()
            onNodeWithText("BUDGET CODE  (optional)").assertExists()
        }
    }

    /** Edit mode is full width, as the web's is: the rail goes, the tips and insert rails come. */
    @Test
    fun `edit mode drops the rail and offers the section controls`() {
        runComposeUiTest {
            setContent { ZillitTheme { FormConfigPage(state(saved.copy(editing = true))) {} } }
            onNodeWithText("Modules").assertDoesNotExist()
            onNodeWithText("Edit Fields").assertExists()
            onNodeWithText("Double-click", substring = true).assertExists()
            onAllNodesWithText("Add Custom Field").assertCountEquals(2)
            onAllNodesWithText("Add Custom Column").assertCountEquals(1)
            // A rail above the first section and one after each of the three.
            onAllNodesWithContentDescription("Insert section here").assertCountEquals(4)
            onNodeWithText("1/2 visible").assertExists()
            // Only the production's own section can be removed or renamed.
            onNodeWithContentDescription("Remove Extras").assertExists()
            onNodeWithContentDescription("Remove Header").assertDoesNotExist()
            onNodeWithContentDescription("Rename Extras").assertExists()
        }
    }

    @Test
    fun `clicking a field in edit mode asks for its panel`() {
        val events = mutableListOf<AccountHubEvent>()
        runComposeUiTest {
            setContent { ZillitTheme { FormConfigPage(state(saved.copy(editing = true))) { events += it } } }
            onNodeWithText("VENDOR *").performClick()
        }
        assertTrue(AccountHubEvent.FocusFormField("header", "vendor") in events)
    }

    @Test
    fun `unsaved edits kept after leaving edit mode are said out loud`() {
        val edited = template.addField("extras", "PO Reference", "text")
        runComposeUiTest {
            setContent { ZillitTheme { FormConfigPage(state(saved.copy(template = edited))) {} } }
            onNodeWithText("Unsaved changes").assertExists()
            onNodeWithText("Save changes").assertExists()
            onNodeWithText("Discard").assertExists()
        }
    }

    /** A system field shows its type rather than offering to change it, and Remove takes it off the form. */
    @Test
    fun `a system field's panel does not offer its type`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    FormConfigPage(state(saved.copy(editing = true, focus = FieldFocus("header", "vendor")))) {}
                }
            }
            onNodeWithText("Header — Field #1").assertExists()
            onNodeWithText("SYSTEM").assertExists()
            onNodeWithText("A system field's type is part of the module.").assertExists()
            onNodeWithText("Saved as vendor").assertExists()
            onNodeWithContentDescription("Take Vendor off the form").assertExists()
            // A system field stays in the section the module reads it from.
            onNodeWithText("SECTION").assertDoesNotExist()
        }
    }

    /** The panel's pickers are menus, which is where a layout throws. */
    @Test
    fun `a custom field's panel opens its type menu and offers another section`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    FormConfigPage(state(saved.copy(editing = true, focus = FieldFocus("extras", "budget_code")))) {}
                }
            }
            onNodeWithText("CUSTOM").assertExists()
            onNodeWithText("SECTION").assertExists()
            onNodeWithContentDescription("Delete Budget Code").assertExists()
            onNode(hasTextExactly("Text") and hasClickAction()).performClick()
            onNodeWithText("Email").assertExists()
        }
    }

    @Test
    fun `the add-a-field panel shows the key the name becomes and brings system fields back`() {
        val events = mutableListOf<AccountHubEvent>()
        val adding = saved.copy(
            editing = true,
            focus = FieldFocus("header", null),
            draft = NewFieldDraft(name = "Budget Code"),
            systemFieldsOpen = true,
        )
        runComposeUiTest {
            setContent { ZillitTheme { FormConfigPage(state(adding)) { events += it } } }
            onNodeWithText("New Field").assertExists()
            onNodeWithText("Saved as budget_code").assertExists()
            onNodeWithText("SYSTEM FIELDS (1)").assertExists()
            onNodeWithText("Notes").assertExists()
            onNode(hasContentDescription("Add Notes back to this form") and hasClickAction()).performClick()
        }
        assertTrue(AccountHubEvent.RestoreFormField("header", "notes") in events)
    }

    @Test
    fun `the rearrange panel lists sections, then one section's fields`() {
        val events = mutableListOf<AccountHubEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme { FormConfigPage(state(saved.copy(editing = true, rearrange = true))) { events += it } }
            }
            onNodeWithText("Drag sections to reorder, click to see fields").assertExists()
            onNodeWithContentDescription("Move Header up").assertExists()
            onNodeWithText("Extras").performClick()
        }
        assertTrue(AccountHubEvent.PickRearrangeSection("extras") in events)

        runComposeUiTest {
            setContent {
                ZillitTheme {
                    FormConfigPage(
                        state(saved.copy(editing = true, rearrange = true, rearrangeSection = "header")),
                    ) {}
                }
            }
            onNodeWithText("Drag fields to reorder").assertExists()
            onNodeWithText("Sections").assertExists()
            // Only what is on the form: Notes is hidden.
            onNodeWithContentDescription("Move Vendor up").assertExists()
            onNodeWithContentDescription("Move Notes up").assertDoesNotExist()
        }
    }

    /** A section dragged below another is dropped in that section's place, by key. */
    @Test
    fun `dragging a section in the rearrange panel moves it`() {
        val events = mutableListOf<AccountHubEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme { FormConfigPage(state(saved.copy(editing = true, rearrange = true))) { events += it } }
            }
            onNodeWithText("Header").performTouchInput {
                down(center)
                repeat(DRAG_STEPS) { moveBy(Offset(0f, DRAG_STEP)) }
                up()
            }
        }
        val move = events.filterIsInstance<AccountHubEvent.MoveFormSection>().singleOrNull()
        assertEquals("header", move?.fromKey)
        assertTrue(move?.toKey in setOf("line_items", "extras"))
        // A drag is not a click: it opens nothing.
        assertTrue(events.none { it is AccountHubEvent.PickRearrangeSection })
    }

    @Test
    fun `adding a section says where it goes`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    FormConfigPage(state(saved.copy(editing = true, composer = SectionComposer("header", "")))) {}
                }
            }
            // The dialog's title and its confirm button.
            onAllNodesWithText("Add Section").assertCountEquals(2)
            onNodeWithText("After Header").assertExists()
            onNodeWithText("Enter a name for the new section").assertExists()
        }
    }

    /** The web's delete confirmation, word for word. */
    @Test
    fun `deleting a section repeats the web's warning`() {
        val extras = template.section("extras")!!
        runComposeUiTest {
            setContent {
                ZillitTheme { FormConfigPage(state(saved.copy(editing = true, removingSection = extras))) {} }
            }
            onNodeWithText("Delete Section").assertExists()
            onNodeWithText("All fields in this section will be removed", substring = true).assertExists()
        }
    }

    /** The reset says what it destroys, because it applies without a save. */
    @Test
    fun `the reset confirmation names what it throws away`() {
        runComposeUiTest {
            setContent { ZillitTheme { FormConfigPage(state(saved.copy(confirmingReset = true))) {} } }
            onNodeWithText("Reset to defaults?").assertExists()
            onNodeWithText("for everyone on this production", substring = true).assertExists()
        }
    }

    @Test
    fun `switching module with unsaved edits names the module it would open`() {
        val edited = template.addField("extras", "PO Reference", "text")
        val asking = saved.copy(
            template = edited,
            discard = DiscardIntent.Switch(com.zillit.desktop.core.forms.FormModule.CashExpenses),
        )
        runComposeUiTest {
            setContent { ZillitTheme { FormConfigPage(state(asking)) {} } }
            onNodeWithText("Discard unsaved changes?").assertExists()
            onNodeWithText("open Petty Cash Expenses", substring = true).assertExists()
            onNodeWithText("Keep editing").assertExists()
        }
    }

    @Test
    fun `a module with no template says so`() {
        runComposeUiTest {
            setContent { ZillitTheme { FormConfigPage(state(FormConfigState())) {} } }
            onNodeWithText("No form template available for Purchase Orders.").assertExists()
        }
    }

    @Test
    fun `a template that could not be read offers a retry`() {
        val events = mutableListOf<AccountHubEvent>()
        runComposeUiTest {
            setContent { ZillitTheme { FormConfigPage(state(FormConfigState(loadFailed = true))) { events += it } } }
            onNodeWithText("Retry").performClick()
        }
        assertTrue(AccountHubEvent.ReloadFormTemplate in events)
    }

    /** The accounts department approves everyone else's documents and is not offered a chain of its own. */
    @Test
    fun `the scope question offers departments but not accounts`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    FormConfigPage(state(saved.copy(scopeModal = ScopeModalState(ApprovalScope.Department)))) {}
                }
            }
            onNodeWithText("How would you like to configure approvers?").assertExists()
            onNodeWithText("Choose a department...").performClick()
            onNodeWithText("Camera").assertExists()
            onNodeWithText("Accounts").assertDoesNotExist()
        }
    }

    /** A chain opened from this page is shown here, in this page's words. */
    @Test
    fun `a chain opened from forms shows the forms breadcrumb`() {
        val chain = ApprovalConfig(
            module = ApprovalModule.PurchaseOrders,
            scope = ApprovalScope.All,
            tiers = listOf(ApprovalTier(order = 1, rules = listOf(ApprovalRule(type = "default")))),
        )
        val approvals = ApprovalsState(builder = ApprovalBuilder(chain, chain, origin = BuilderOrigin.Forms))
        runComposeUiTest {
            setContent { ZillitTheme { FormConfigPage(state(saved, approvals)) {} } }
            onNodeWithText("FORMS").assertExists()
            onNodeWithText("All Departments").assertExists()
            onNodeWithText("Changes will apply uniformly", substring = true).assertExists()
            onNodeWithText("Level 1").assertExists()
        }
    }

    private companion object {
        const val DRAG_STEPS = 12
        const val DRAG_STEP = 14f
    }
}
