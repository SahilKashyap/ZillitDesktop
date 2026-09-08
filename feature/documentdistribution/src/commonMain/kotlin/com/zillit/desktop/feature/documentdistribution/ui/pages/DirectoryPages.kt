package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.gatedClick
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.parseRecipients

/**
 * Saved recipient sets.
 *
 * Editing a list is editing its whole recipient set — the endpoint replaces
 * rather than patches, so the editor sends everyone every time. That is why
 * the dialog holds the full membership as text rather than offering
 * add/remove: a partial send here silently drops whoever is not in it.
 */
@Composable
fun ListsPage(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    var editing by remember { mutableStateOf<DistributionList?>(null) }

    FixedPage {
        ZillitSectionCard(
            title = "Distribution lists",
            icon = ZillitIcons.Users,
            padded = false,
            action = {
                    ZillitButton(
                        text = "New list",
                        onClick = gatedClick(state.viewer.canPost, { onEvent(ask) }) {
                            editing = DistributionList(id = "", name = "")
                        },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                    )
            },
        ) {
            ZillitDataTable(
                rows = state.lists,
                key = { it.id },
                loading = state.loading,
                onRowClick = { editing = it },
                columns = listOf(
                    textColumn("Name", ColumnWidth.Weight(2f)) { it.name },
                    textColumn("Recipients", ColumnWidth.Fixed(COUNT_COLUMN.dp), numeric = true) {
                        it.recipients.size.toString()
                    },
                    textColumn("Members", ColumnWidth.Weight(3f), muted = true) { list ->
                        // A preview rather than the whole membership: forty
                        // addresses in a table cell is not a list anyone reads.
                        list.recipients.take(PREVIEW).joinToString(", ") {
                            it.name.ifBlank { it.email }
                        } + if (list.recipients.size > PREVIEW) " +${list.recipients.size - PREVIEW}" else ""
                    },
                    deleteColumn(state.viewer.canPost, { onEvent(ask) }) { list ->
                        onEvent(DocDistEvent.DeleteList(list.id))
                    },
                ),
                emptyTitle = "No distribution lists",
                emptyMessage = "Save a set of recipients so the next send is one click.",
            )
        }
    }

    ListEditorDialog(
        list = editing,
        onDismiss = { editing = null },
        onSave = { saved ->
            editing = null
            onEvent(DocDistEvent.SaveList(saved))
        },
    )
}

@Composable
private fun ListEditorDialog(
    list: DistributionList?,
    onDismiss: () -> Unit,
    onSave: (DistributionList) -> Unit,
) {
    var name by remember(list) { mutableStateOf(list?.name.orEmpty()) }
    var members by remember(list) {
        mutableStateOf(list?.recipients.orEmpty().joinToString("\n") { it.email })
    }

    ZillitDialogShell(
        title = if (list?.id.isNullOrBlank()) "New distribution list" else "Edit list",
        subtitle = "One address per line. Saving replaces the whole membership.",
        visible = list != null,
        onDismiss = onDismiss,
        icon = ZillitIcons.Users,
    ) {
        ZillitTextField(value = name, onValueChange = { name = it }, label = "List name")
        ZillitTextField(
            value = members,
            onValueChange = { members = it },
            label = "Recipients",
            placeholder = "ada@example.com",
            singleLine = false,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Tertiary)
            ZillitButton(
                text = "Save",
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        DistributionList(
                            id = list?.id.orEmpty(),
                            name = name.trim(),
                            recipients = parseRecipients(members),
                        ),
                    )
                },
            )
        }
    }
}

/** Everyone this production has ever sent to. */
@Composable
fun AddressBookPage(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    var editing by remember { mutableStateOf<Contact?>(null) }

    FixedPage {
        ZillitSectionCard(
            title = "Address book",
            icon = ZillitIcons.User,
            padded = false,
            action = {
                    ZillitButton(
                        text = "Add contact",
                        onClick = gatedClick(state.viewer.canPost, { onEvent(ask) }) { editing = Contact(email = "") },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.UserPlus,
                    )
            },
        ) {
            ZillitDataTable(
                rows = state.contacts,
                key = { it.email },
                loading = state.loading,
                onRowClick = { editing = it },
                columns = listOf(
                    textColumn("Name", ColumnWidth.Weight(2f)) { it.name.ifBlank { "—" } },
                    textColumn("Email", ColumnWidth.Weight(2f)) { it.email },
                    textColumn("Job", ColumnWidth.Weight(1f), muted = true) {
                        it.jobTitle.ifBlank { "—" }
                    },
                    deleteColumn(state.viewer.canPost, { onEvent(ask) }) { contact ->
                        onEvent(DocDistEvent.DeleteContact(contact.email))
                    },
                ),
                emptyTitle = "No contacts yet",
                emptyMessage = "Addresses used in a distribution are remembered here.",
            )
        }
    }

    ContactEditorDialog(
        contact = editing,
        onDismiss = { editing = null },
        onSave = { saved ->
            editing = null
            onEvent(DocDistEvent.SaveContact(saved))
        },
    )
}

@Composable
private fun ContactEditorDialog(
    contact: Contact?,
    onDismiss: () -> Unit,
    onSave: (Contact) -> Unit,
) {
    var name by remember(contact) { mutableStateOf(contact?.name.orEmpty()) }
    var email by remember(contact) { mutableStateOf(contact?.email.orEmpty()) }
    var job by remember(contact) { mutableStateOf(contact?.jobTitle.orEmpty()) }

    ZillitDialogShell(
        title = if (contact?.email.isNullOrBlank()) "Add contact" else "Edit contact",
        visible = contact != null,
        onDismiss = onDismiss,
        icon = ZillitIcons.UserPlus,
    ) {
        ZillitTextField(value = name, onValueChange = { name = it }, label = "Name")
        ZillitTextField(value = email, onValueChange = { email = it }, label = "Email")
        ZillitTextField(value = job, onValueChange = { job = it }, label = "Job title")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Tertiary)
            ZillitButton(
                text = "Save",
                enabled = email.isNotBlank(),
                onClick = { onSave(Contact(email.trim(), name.trim(), job.trim())) },
            )
        }
    }
}

/** Reusable subject + body pairs the composer can load. */
@Composable
fun TemplatesPage(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    var editing by remember { mutableStateOf<EmailTemplate?>(null) }

    FixedPage {
        ZillitSectionCard(
            title = "Email templates",
            icon = ZillitIcons.Mail,
            padded = false,
            action = {
                    ZillitButton(
                        text = "New template",
                        onClick = gatedClick(state.viewer.canPost, { onEvent(ask) }) {
                            editing = EmailTemplate(id = "", name = "")
                        },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                    )
            },
        ) {
            ZillitDataTable(
                rows = state.templates,
                key = { it.id },
                loading = state.loading,
                onRowClick = { editing = it },
                columns = listOf(
                    textColumn("Name", ColumnWidth.Weight(1f)) { it.name },
                    textColumn("Subject", ColumnWidth.Weight(2f), muted = true) {
                        it.subject.ifBlank { "—" }
                    },
                    deleteColumn(state.viewer.canPost, { onEvent(ask) }) { template ->
                        onEvent(DocDistEvent.DeleteTemplate(template.id))
                    },
                ),
                emptyTitle = "No templates",
                emptyMessage = "Save a subject and body you send often.",
            )
        }
    }

    TemplateEditorDialog(
        template = editing,
        onDismiss = { editing = null },
        onSave = { saved ->
            editing = null
            onEvent(DocDistEvent.SaveTemplate(saved))
        },
    )
}

@Composable
private fun TemplateEditorDialog(
    template: EmailTemplate?,
    onDismiss: () -> Unit,
    onSave: (EmailTemplate) -> Unit,
) {
    var name by remember(template) { mutableStateOf(template?.name.orEmpty()) }
    var subject by remember(template) { mutableStateOf(template?.subject.orEmpty()) }
    var body by remember(template) { mutableStateOf(template?.bodyHtml.orEmpty()) }

    ZillitDialogShell(
        title = if (template?.id.isNullOrBlank()) "New template" else "Edit template",
        visible = template != null,
        onDismiss = onDismiss,
        icon = ZillitIcons.Mail,
    ) {
        ZillitTextField(value = name, onValueChange = { name = it }, label = "Template name")
        ZillitTextField(value = subject, onValueChange = { subject = it }, label = "Subject")
        ZillitTextField(
            value = body,
            onValueChange = { body = it },
            label = "Body",
            singleLine = false,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Tertiary)
            ZillitButton(
                text = "Save",
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        EmailTemplate(
                            id = template?.id.orEmpty(),
                            name = name.trim(),
                            subject = subject,
                            bodyHtml = body,
                        ),
                    )
                },
            )
        }
    }
}

/**
 * The trailing delete column, or a spacer when this viewer cannot delete.
 *
 * A column rather than a conditional row so the three directory tables line up
 * with each other whichever rights the viewer holds — a table that loses its
 * last column re-flows every other one.
 */
private fun <T> deleteColumn(
    granted: Boolean,
    onDenied: () -> Unit,
    onDelete: (T) -> Unit,
): TableColumn<T> =
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(ACTION_COLUMN.dp),
        cell = { row ->
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Delete",
                onClick = gatedClick(granted, onDenied) { onDelete(row) },
                tint = ZillitTheme.colors.danger,
            )
        },
    )

/** The one thing a reader without posting rights can usefully do here. */
private val ask = DocDistEvent.RequestRights(RightsKind.Post)

private const val COUNT_COLUMN = 110
private const val ACTION_COLUMN = 56
private const val PREVIEW = 4
