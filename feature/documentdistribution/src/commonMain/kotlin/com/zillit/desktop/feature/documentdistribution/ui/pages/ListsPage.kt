package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.permissions.gatedClick
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.CsvContact
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.ui.CsvImportState
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState

/**
 * Distribution lists — the web's `PresetManagerModal`: an overview of the
 * smart list and the custom lists, and in place of it one list's editor
 * when a row is opened.
 */
@Composable
fun ListsPage(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val detail = state.listDetail
    FixedPage {
        if (detail != null) ListDetail(state, onEvent) else ListsOverview(state, onEvent)
    }
    CsvImportDialog(state.listDetail?.csv ?: state.listEditor?.csv, onEvent)
}

@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ListsOverview(
    state: DocDistUiState,
    onEvent: (DocDistEvent) -> Unit,
) {
    val c = ZillitTheme.colors
    val canPost = state.viewer.canPost
    val canDownload = state.viewer.canDownload
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(text = "Distribution lists", style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = "${state.lists.size} custom list" + (if (state.lists.size == 1) "" else "s") +
                    " · smart lists update themselves",
                style = ZillitTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }
        ZillitSearchField(
            value = state.listsSearch,
            onValueChange = { onEvent(DocDistEvent.SearchLists(it)) },
            placeholder = "Search for a list",
            modifier = Modifier.width(SEARCH_WIDTH.dp),
        )
        ZillitButton(
            text = "New list",
            onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(
                DocDistEvent.NewListRow(state.newListName == null),
            ) },
            leadingIcon = ZillitIcons.Add,
            size = ButtonSize.Small,
        )
    }
    state.newListName?.let { name ->
        Row(
            Modifier.fillMaxWidth().clip(ZillitTheme.shapes.large).background(c.accentSoft).padding(
                ZillitTheme.spacing.md,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIconButton(ZillitIcons.Close, "Cancel", { onEvent(DocDistEvent.NewListRow(false)) })
            ZillitTextField(
                value = name,
                onValueChange = { onEvent(DocDistEvent.EditNewListName(it)) },
                placeholder = "Enter a list name",
                onImeAction = { onEvent(DocDistEvent.CreateListInline) },
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = "Create",
                onClick = { onEvent(DocDistEvent.CreateListInline) },
                enabled = name.isNotBlank(),
                loading = state.creatingList,
                size = ButtonSize.Small,
            )
        }
    }
    ZillitSectionCard(title = "Smart lists", icon = ZillitIcons.Siren, meta = "Automatically updated", padded = false) {
        HoverRow(
            onClick = { onEvent(
                DocDistEvent.Open(com.zillit.desktop.feature.documentdistribution.ui.DocDistDestination.AddressBook),
            ) },
            padding = ZillitTheme.spacing.lg,
        ) {
            ZillitIcon(icon = ZillitIcons.Users, tint = c.accent)
            ZillitText(
                text = "All contacts",
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = "${state.contacts.size} " + if (state.contacts.size == 1) "person" else "people",
                style = ZillitTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            ZillitTooltip("Open the address book") { ZillitIcon(
                icon = ZillitIcons.Settings,
                tint = c.textMuted,
                size = 16.dp,
            ) }
        }
    }
    ZillitSectionCard(
        title = "Custom lists",
        icon = ZillitIcons.Mail,
        meta = "Manage your own lists",
        padded = false,
        modifier = Modifier.weight(1f),
    ) {
        ZillitDataTable(
            rows = state.visibleLists,
            key = { it.id },
            loading = state.loading,
            onRowClick = { onEvent(DocDistEvent.OpenList(it.id)) },
            columns = listOf(
                TableColumn(header = "List name", width = ColumnWidth.Weight(2f)) { list ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    ) {
                        ZillitIcon(icon = ZillitIcons.Users, tint = c.accent, size = 16.dp)
                        ZillitText(
                            text = list.name,
                            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                        )
                    }
                },
                textColumn("Recipients", ColumnWidth.Fixed(RECIPIENTS_COLUMN.dp)) { "${it.recipients.size} people" },
                textColumn("Last updated on", ColumnWidth.Fixed(UPDATED_COLUMN.dp), muted = true) {
                    EpochDate.dateTime(it.updatedAt).ifBlank { "—" }
                },
                TableColumn(header = "", width = ColumnWidth.Fixed(ACTIONS_COLUMN.dp)) { list ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                        ZillitTooltip(if (canDownload) "Download as CSV" else "No download rights") {
                            if (state.exportingListId == list.id) ZillitSpinner(size = 16.dp)
                            else ZillitIconButton(
                                ZillitIcons.Download,
                                "Export ${list.name}",
                                gatedClick(canDownload, { onEvent(askDownload) }) { onEvent(
                                    DocDistEvent.ExportList(list.id),
                                ) },
                            )
                        }
                        ZillitTooltip("Open") { ZillitIconButton(
                            ZillitIcons.Settings,
                            "Open ${list.name}",
                            { onEvent(DocDistEvent.OpenList(list.id)) },
                        ) }
                    }
                },
            ),
            emptyTitle = if (state.listsSearch.isNotBlank()) "No matches" else "No custom lists yet",
            emptyMessage = if (state.listsSearch.isNotBlank()) {
                null
            } else {
                "Create one to save a set of recipients for the next send."
            },
        )
    }
}

@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ListDetail(
    state: DocDistUiState,
    onEvent: (DocDistEvent) -> Unit,
) {
    val detail = state.listDetail ?: return
    val c = ZillitTheme.colors
    val canPost = state.viewer.canPost
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitButton(
            text = "Back",
            onClick = { onEvent(DocDistEvent.CloseList) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.ArrowLeft,
        )
        ZillitStatusPill(label = "Custom list", tone = StatusTone.Pending)
        Box(Modifier.weight(1f))
        ZillitButton(
            text = "Remove this list",
            onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(
                DocDistEvent.ConfirmRemoveList(detail.listId),
            ) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Trash,
        )
        ZillitButton(
            text = "Share some documents",
            onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(
                DocDistEvent.ComposeWithList(detail.listId),
            ) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Send,
            enabled = detail.recipients.isNotEmpty(),
        )
    }
    FieldLabel("List name")
    ZillitTextField(
        value = detail.name,
        onValueChange = { onEvent(DocDistEvent.EditListName(it)) },
        placeholder = "List name",
        readOnly = !canPost,
    )
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        ZillitSectionCard(
            modifier = Modifier.width(FORM_WIDTH.dp),
            title = "Add a recipient",
            icon = ZillitIcons.UserPlus,
        ) {
            RecipientForm(
                email = detail.emailInput,
                name = detail.nameInput,
                job = detail.jobInput,
                contacts = state.contacts.notIn(detail.recipients),
                enabled = canPost,
                onChange = { e, n, j -> onEvent(DocDistEvent.EditListRecipientInput(e, n, j)) },
                onPick = { onEvent(DocDistEvent.PickListContact(it)) },
                onAdd = { onEvent(DocDistEvent.AddListRecipient) },
            )
            FieldLabel("Import a file (.csv)", Modifier.padding(top = ZillitTheme.spacing.md))
            ZillitText(
                text = "Columns: name, email, job",
                style = ZillitTheme.typography.bodySmall,
                color = c.textMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitButton(
                    text = "Browse…",
                    onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.PickCsv) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Upload,
                )
                ZillitButton(
                    text = "Download template",
                    onClick = { onEvent(DocDistEvent.DownloadCsvTemplate) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            Box(Modifier.weight(1f))
            ZillitButton(
                text = "Save changes",
                onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.SaveList) },
                loading = detail.saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ZillitSectionCard(
            modifier = Modifier.weight(1f),
            title = "Recipients",
            icon = ZillitIcons.Users,
            padded = false,
            action = { ZillitBadge(count = detail.recipients.size, background = c.accent, cap = null) },
        ) {
            RecipientsTable(detail.recipients, canPost) { onEvent(DocDistEvent.RemoveListRecipient(it)) }
        }
    }
}

/** Email (with the address book beneath it), name, job, and Add. */
@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
internal fun RecipientForm(
    email: String,
    name: String,
    job: String,
    contacts: List<Contact>,
    enabled: Boolean,
    onChange: (email: String, name: String, job: String) -> Unit,
    onPick: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val c = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitTextField(
            value = email,
            onValueChange = { onChange(it, name, job) },
            placeholder = "Search address book or type an email",
            leadingIcon = ZillitIcons.Search,
            enabled = enabled,
            onImeAction = onAdd,
        )
        val q = email.trim().lowercase()
        val matches = if (q.isEmpty()) {
            emptyList()
        } else {
            contacts.filter { it.email.lowercase().contains(q) || it.name.lowercase().contains(q) }
                .sortedBy { it.displayName.lowercase() }
                .take(SUGGESTIONS)
        }
        if (
            matches.isNotEmpty() && matches.none { it.email.equals(email.trim(), ignoreCase = true) && it.name == name }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = SUGGESTIONS_HEIGHT.dp)
                    .clip(ZillitTheme.shapes.medium)
                    .background(c.surfaceRaised)
                    .border(0.5.dp, c.border, ZillitTheme.shapes.medium)
                    .padding(ZillitTheme.spacing.xs),
            ) {
                matches.forEach { contact ->
                    HoverRow(onClick = { onPick(contact.email) }, padding = ZillitTheme.spacing.sm) {
                        Column {
                            ZillitText(text = contact.displayName, style = ZillitTheme.typography.label, maxLines = 1)
                            if (contact.name.isNotBlank()) ZillitText(
                                text = contact.email,
                                style = ZillitTheme.typography.bodySmall,
                                color = c.textMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        ZillitTextField(
            value = name,
            onValueChange = { onChange(email, it, job) },
            placeholder = "Name",
            enabled = enabled,
            onImeAction = onAdd,
        )
        ZillitTextField(
            value = job,
            onValueChange = { onChange(email, name, it) },
            placeholder = "Job",
            enabled = enabled,
            onImeAction = onAdd,
        )
        ZillitButton(
            text = "Add to the list",
            onClick = onAdd,
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Add,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun RecipientsTable(recipients: List<Recipient>, canRemove: Boolean, onRemove: (String) -> Unit) {
    ZillitDataTable(
        rows = recipients.sortedBy { it.name.ifBlank { it.email }.lowercase() },
        key = { it.email },
        columns = listOf(
            textColumn("Email", ColumnWidth.Weight(2f)) { it.email },
            textColumn("Name", ColumnWidth.Weight(1.4f)) { it.name.ifBlank { "—" } },
            textColumn("Job", ColumnWidth.Weight(1f), muted = true) { it.jobTitle.ifBlank { "—" } },
            TableColumn(header = "", width = ColumnWidth.Fixed(ROW_ACTION.dp)) { r ->
                ZillitIconButton(
                    ZillitIcons.Trash,
                    "Remove ${r.email}",
                    { onRemove(r.email) },
                    enabled = canRemove,
                    tint = ZillitTheme.colors.danger,
                )
            },
        ),
        emptyTitle = "No recipients yet",
        emptyMessage = "Add some on the left, or import a CSV.",
    )
}

/** The parsed CSV, rows tagged valid / duplicate / invalid, awaiting "Add". */
@Composable
private fun CsvImportDialog(csv: CsvImportState?, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    val count = csv?.importable?.size ?: 0
    ZillitDialogShell(
        title = "Import recipients",
        subtitle = csv?.fileName?.let { "From $it · expected columns: name, email, job" },
        visible = csv != null,
        onDismiss = { onEvent(DocDistEvent.CancelCsv) },
        icon = ZillitIcons.Upload,
        width = 600.dp,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.CancelCsv) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (count > 0) "Add $count contact" + (if (count == 1) "" else "s") else "Add contacts",
                onClick = { onEvent(DocDistEvent.ConfirmCsv) },
                enabled = count > 0,
            )
        },
    ) {
        csv?.rows?.forEach { row -> CsvRow(row) }
        ZillitText(
            text = "Single-column files are treated as emails. Google & Outlook contact exports are also supported.",
            style = ZillitTheme.typography.bodySmall,
            color = c.textMuted,
        )
    }
}

@Composable
private fun CsvRow(row: CsvContact) {
    val c = ZillitTheme.colors
    val (icon, tint, badge) = when {
        !row.valid -> Triple(ZillitIcons.Close, c.danger, "invalid email")
        row.duplicate -> Triple(ZillitIcons.Check, c.warning, "duplicate")
        else -> Triple(ZillitIcons.Check, c.success, null)
    }
    HoverRow(padding = ZillitTheme.spacing.sm) {
        ZillitIcon(icon = icon, tint = tint, size = 16.dp)
        Column(Modifier.weight(1f)) {
            if (row.name.isNotBlank()) ZillitText(text = row.name, style = ZillitTheme.typography.label, maxLines = 1)
            ZillitText(
                text = row.email.ifBlank { "(empty)" },
                style = ZillitTheme.typography.bodySmall,
                color = c.textSecondary,
                maxLines = 1,
            )
        }
        badge?.let { ZillitStatusPill(label = it, tone = if (row.valid) StatusTone.Pending else StatusTone.Rejected) }
    }
}

/** The composer's "Create new list" — build one without leaving the send. */
@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
internal fun ListEditorDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val editor = state.listEditor
    ZillitDialogShell(
        title = "Create distribution list",
        visible = editor != null,
        onDismiss = { onEvent(DocDistEvent.CloseListEditor) },
        icon = ZillitIcons.Users,
        width = 640.dp,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.CloseListEditor) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Create list",
                onClick = { onEvent(DocDistEvent.SaveListEditor) },
                loading = editor?.saving == true,
                enabled = editor?.name?.isNotBlank() == true && editor.recipients.isNotEmpty(),
            )
        },
    ) {
        if (editor == null) return@ZillitDialogShell
        FieldLabel("Distribution list name *")
        ZillitTextField(
            value = editor.name,
            onValueChange = { onEvent(DocDistEvent.EditListEditor(name = it)) },
            placeholder = "e.g. Production team, Cast leads",
        )
        FieldLabel("Description")
        ZillitTextField(
            value = editor.description,
            onValueChange = { onEvent(DocDistEvent.EditListEditor(description = it)) },
            placeholder = "What this list is for",
        )
        FieldLabel("Add recipients")
        RecipientForm(
            email = editor.emailInput,
            name = editor.nameInput,
            job = editor.jobInput,
            contacts = state.contacts.notIn(editor.recipients),
            enabled = true,
            onChange = { e, n, j -> onEvent(DocDistEvent.EditListEditorInput(e, n, j)) },
            onPick = { onEvent(DocDistEvent.PickListEditorContact(it)) },
            onAdd = { onEvent(DocDistEvent.AddListEditorRecipient) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitButton(
                text = "Import from CSV",
                onClick = { onEvent(DocDistEvent.PickCsvForListEditor) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.File,
            )
            ZillitButton(
                text = "Download template",
                onClick = { onEvent(DocDistEvent.DownloadCsvTemplate) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        if (editor.recipients.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(max = EDITOR_TABLE_HEIGHT.dp)) {
                RecipientsTable(editor.recipients, canRemove = true) { onEvent(
                    DocDistEvent.RemoveListEditorRecipient(it),
                ) }
            }
        }
    }
}

/** The contacts not already among [recipients], for the add-recipient suggestions. */
internal fun List<Contact>.notIn(recipients: List<Recipient>): List<Contact> =
    filter { contact -> recipients.none { it.email.equals(contact.email, ignoreCase = true) } }

private const val SEARCH_WIDTH = 240
private const val RECIPIENTS_COLUMN = 130
private const val UPDATED_COLUMN = 190
private const val ACTIONS_COLUMN = 90
private const val FORM_WIDTH = 320
private const val ROW_ACTION = 56
private const val SUGGESTIONS = 8
private const val SUGGESTIONS_HEIGHT = 220
private const val EDITOR_TABLE_HEIGHT = 240
