package com.zillit.desktop.feature.accounthub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.FieldFocus
import com.zillit.desktop.feature.accounthub.ui.FormConfigState
import com.zillit.desktop.feature.accounthub.ui.NewFieldDraft
import com.zillit.desktop.feature.accounthub.ui.pages.FormConfigPage
import kotlin.test.Test

/**
 * Composes the form editor in each state, light and dark.
 *
 * Both themes because a colour defined in one and not the other is invisible
 * until somebody switches; the dialogs opened rather than merely constructed,
 * because a menu inside one is where this design system throws.
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
                key = "lines",
                label = "Line Items",
                order = 2,
                systemDefault = true,
                fields = listOf(FormField(label = "amount", name = "Amount", type = "number", order = 1)),
            ),
            FormSection(
                key = FormTemplate.TERMS_SECTION,
                label = "Terms of Engagement",
                order = 3,
                systemDefault = true,
            ),
        ),
    )

    private fun state(config: FormConfigState) = AccountHubUiState(formConfig = config)

    @Test
    fun `the preview composes in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = dark) {
                        FormConfigPage(state(FormConfigState(template = template, saved = template))) {}
                    }
                }
                onNodeWithText("Forms Configuration").assertExists()
                // Section eyebrows are drawn in the web's uppercase mono label.
                onNodeWithText("HEADER").assertExists()
                onNodeWithText("LINE ITEMS").assertExists()
                // An empty terms section is skipped in the preview, as on the web.
                onNodeWithText("TERMS OF ENGAGEMENT").assertDoesNotExist()
            }
        }
    }

    @Test
    fun `edit mode offers the section and field controls`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    FormConfigPage(
                        state(FormConfigState(template = template, saved = template, editing = true)),
                    ) {}
                }
            }
            // One per section, and there are two configurable ones.
            onAllNodesWithText("Add Custom Field").assertCountEquals(2)
            // A rail above the first section and one after each: three.
            onAllNodesWithText("Insert section here").assertCountEquals(3)
            // The header shows how much of the section is on the form.
            onNodeWithText("1/2 visible").assertExists()
        }
    }

    @Test
    fun `an unsaved edit is said out loud`() {
        val edited = template.addField("lines", "Budget Code", "text")
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    FormConfigPage(
                        state(FormConfigState(template = edited, saved = template, editing = true)),
                    ) {}
                }
            }
            onNodeWithText("Unsaved changes", substring = true).assertExists()
        }
    }

    /** The inspector's pickers are menus, which is where a layout throws. */
    @Test
    fun `the field inspector opens its type menu`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    FormConfigPage(
                        state(
                            FormConfigState(
                                template = template,
                                saved = template,
                                editing = true,
                                focus = FieldFocus("lines", "amount"),
                            ),
                        ),
                    ) {}
                }
            }
            onNodeWithText("Delete this custom field").assertExists()
            onNodeWithText("Move to another section").assertExists()
            // The picker, not the type pill on the row behind the dialog:
            // both read "Number", and only one of them can be clicked.
            onNode(hasTextExactly("Number") and hasClickAction()).performClick()
            onNodeWithText("Email").assertExists()
        }
    }

    /** A system field says its type instead of offering to change it. */
    @Test
    fun `a system field's inspector does not offer a type`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    FormConfigPage(
                        state(
                            FormConfigState(
                                template = template,
                                saved = template,
                                editing = true,
                                focus = FieldFocus("header", "vendor"),
                            ),
                        ),
                    ) {}
                }
            }
            onNodeWithText("cannot be changed here", substring = true).assertExists()
            onNodeWithText("Take off the form").assertExists()
        }
    }

    @Test
    fun `the add-a-field panel shows the key the name becomes`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    FormConfigPage(
                        state(
                            FormConfigState(
                                template = template,
                                saved = template,
                                editing = true,
                                focus = FieldFocus("lines", null),
                                draft = NewFieldDraft(name = "Budget Code"),
                            ),
                        ),
                    ) {}
                }
            }
            // The dialog's title plus the two section buttons behind it.
            onAllNodesWithText("Add Custom Field").assertCountEquals(3)
            onNodeWithText("Stored as budget_code").assertExists()
        }
    }

    /** The reset says what it destroys, because it applies without a save. */
    @Test
    fun `the reset confirmation names what it throws away`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    FormConfigPage(
                        state(
                            FormConfigState(
                                template = template,
                                saved = template,
                                confirmingReset = true,
                            ),
                        ),
                    ) {}
                }
            }
            onNodeWithText("Reset Purchase Orders to the defaults?").assertExists()
            onNodeWithText("applies to everybody", substring = true).assertExists()
        }
    }

    /** The hidden system field is offered back from the add-a-field panel, as on the web. */
    @Test
    fun `the add-a-field panel restores a system field taken off the form`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    FormConfigPage(
                        state(
                            FormConfigState(
                                template = template,
                                saved = template,
                                editing = true,
                                focus = FieldFocus("header", null),
                            ),
                        ),
                    ) {}
                }
            }
            onNodeWithText("System Fields").assertExists()
            onNodeWithText("Notes").assertExists()
            onNodeWithText("Restore").assertExists()
        }
    }

    @Test
    fun `a module with no template says so`() {
        runComposeUiTest {
            setContent { ZillitTheme { FormConfigPage(state(FormConfigState())) {} } }
            onNodeWithText("No form for Purchase Orders").assertExists()
        }
    }
}
