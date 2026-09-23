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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.CsvContact
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.ui.CsvImportState
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.plural

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
            ZillitText(text = str(S.dd_lists), style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = plural(
                    state.lists.size,
                    S.desktop_docdist_custom_lists_summary_one,
                    S.desktop_docdist_custom_lists_summary,
                ),
                style = ZillitTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }
        ZillitSearchField(
            value = state.listsSearch,
            onValueChange = { onEvent(DocDistEvent.SearchLists(it)) },
            placeholder = str(S.desktop_docdist_search_for_a_list),
            modifier = Modifier.width(SEARCH_WIDTH.dp),
        )
        ZillitButton(
            text = str(S.dd_new_list),
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
            ZillitIconButton(ZillitIcons.Close, str(S.cancel), { onEvent(DocDistEvent.NewListRow(false)) })
            ZillitTextField(
                value = name,
                onValueChange = { onEvent(DocDistEvent.EditNewListName(it)) },
                placeholder = str(S.desktop_docdist_enter_list_name),
                onImeAction = { onEvent(DocDistEvent.CreateListInline) },
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.create),
                onClick = { onEvent(DocDistEvent.CreateListInline) },
                enabled = name.isNotBlank(),
                loading = state.creatingList,
                size = ButtonSize.Small,
            )
        }
    }
    ZillitSectionCard(
        title = str(S.dd_smart_lists_header),
        icon = ZillitIcons.Siren,
        meta = str(S.dd_auto_updated),
        padded = false,
    ) {
        HoverRow(
            onClick = { onEvent(
                DocDistEvent.Open(com.zillit.desktop.feature.documentdistribution.ui.DocDistDestination.AddressBook),
            ) },
            padding = ZillitTheme.spacing.lg,
        ) {
            ZillitIcon(icon = ZillitIcons.Users, tint = c.accent)
            ZillitText(
                text = str(S.dd_all_contacts),
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = plural(state.contacts.size, S.desktop_docdist_one_person, S.dd_n_people),
                style = ZillitTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            ZillitTooltip(str(S.desktop_docdist_open_address_book)) { ZillitIcon(
                icon = ZillitIcons.Settings,
                tint = c.textMuted,
                size = 16.dp,
            ) }
        }
    }
    ZillitSectionCard(
        title = str(S.dd_custom_lists_header),
        icon = ZillitIcons.Mail,
        meta = str(S.dd_custom_lists_subtitle),
        padded = false,
        modifier = Modifier.weight(1f),
    ) {
        ZillitDataTable(
            rows = state.visibleLists,
            key = { it.id },
            loading = state.loading,
            onRowClick = { onEvent(DocDistEvent.OpenList(it.id)) },
            columns = listOf(
                TableColumn(header = str(S.dd_history_save_list_hint), width = ColumnWidth.Weight(2f)) { list ->
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
                textColumn(
                    str(S.recipients),
                    ColumnWidth.Fixed(RECIPIENTS_COLUMN.dp),
                ) { str(S.dd_n_people, it.recipients.size) },
                textColumn(str(S.desktop_docdist_last_updated_on), ColumnWidth.Fixed(UPDATED_COLUMN.dp), muted = true) {
                    EpochDate.dateTime(it.updatedAt).ifBlank { "—" }
                },
                TableColumn(header = "", width = ColumnWidth.Fixed(ACTIONS_COLUMN.dp)) { list ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                        val downloadHint = if (canDownload) {
                            str(S.desktop_docdist_download_as_csv)
                        } else {
                            str(S.dd_export_no_rights)
                        }
                        ZillitTooltip(downloadHint) {
                            if (state.exportingListId == list.id) ZillitSpinner(size = 16.dp)
                            else ZillitIconButton(
                                ZillitIcons.Download,
                                str(S.desktop_docdist_export_named, list.name),
                                gatedClick(canDownload, { onEvent(askDownload) }) { onEvent(
                                    DocDistEvent.ExportList(list.id),
                                ) },
                            )
                        }
                        ZillitTooltip(str(S.dd_action_open)) { ZillitIconButton(
                            ZillitIcons.Settings,
                            str(S.desktop_drive_open_item, list.name),
                            { onEvent(DocDistEvent.OpenList(list.id)) },
                        ) }
                    }
                },
            ),
            emptyTitle = str(
                if (state.listsSearch.isNotBlank()) {
                    S.dm_picker_empty
                } else {
                    S.desktop_docdist_no_custom_lists_yet
                },
            ),
            emptyMessage = if (state.listsSearch.isNotBlank()) {
                null
            } else {
                str(S.desktop_docdist_no_custom_lists_hint)
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
            text = str(S.back),
            onClick = { onEvent(DocDistEvent.CloseList) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.ArrowLeft,
        )
        ZillitStatusPill(label = str(S.desktop_docdist_custom_list), tone = StatusTone.Pending)
        Box(Modifier.weight(1f))
        ZillitButton(
            text = str(S.desktop_docdist_remove_this_list),
            onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(
                DocDistEvent.ConfirmRemoveList(detail.listId),
            ) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Trash,
        )
        ZillitButton(
            text = str(S.desktop_docdist_share_some_documents),
            onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(
                DocDistEvent.ComposeWithList(detail.listId),
            ) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Send,
            enabled = detail.recipients.isNotEmpty(),
        )
    }
    FieldLabel(str(S.dd_history_save_list_hint))
    ZillitTextField(
        value = detail.name,
        onValueChange = { onEvent(DocDistEvent.EditListName(it)) },
        placeholder = str(S.dd_history_save_list_hint),
        readOnly = !canPost,
    )
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        ZillitSectionCard(
            modifier = Modifier.width(FORM_WIDTH.dp),
            title = str(S.dd_add_recipient),
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
            FieldLabel(str(S.desktop_docdist_import_a_csv_file), Modifier.padding(top = ZillitTheme.spacing.md))
            ZillitText(
                text = str(S.desktop_docdist_csv_columns),
                style = ZillitTheme.typography.bodySmall,
                color = c.textMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitButton(
                    text = str(S.desktop_docdist_browse),
                    onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.PickCsv) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Upload,
                )
                ZillitButton(
                    text = str(S.dd_csv_download_template),
                    onClick = { onEvent(DocDistEvent.DownloadCsvTemplate) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            Box(Modifier.weight(1f))
            ZillitButton(
                text = str(S.dd_action_save_changes),
                onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.SaveList) },
                loading = detail.saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ZillitSectionCard(
            modifier = Modifier.weight(1f),
            title = str(S.recipients),
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
            placeholder = str(S.desktop_docdist_search_address_book_or_email),
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
            placeholder = str(S.name),
            enabled = enabled,
            onImeAction = onAdd,
        )
        ZillitTextField(
            value = job,
            onValueChange = { onChange(email, name, it) },
            placeholder = str(S.dd_field_job),
            enabled = enabled,
            onImeAction = onAdd,
        )
        ZillitButton(
            text = str(S.dd_add_to_list),
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
            textColumn(str(S.email), ColumnWidth.Weight(2f)) { it.email },
            textColumn(str(S.name), ColumnWidth.Weight(1.4f)) { it.name.ifBlank { "—" } },
            textColumn(str(S.dd_field_job), ColumnWidth.Weight(1f), muted = true) { it.jobTitle.ifBlank { "—" } },
            TableColumn(header = "", width = ColumnWidth.Fixed(ROW_ACTION.dp)) { r ->
                ZillitIconButton(
                    ZillitIcons.Trash,
                    str(S.bs_chip_remove, r.email),
                    { onRemove(r.email) },
                    enabled = canRemove,
                    tint = ZillitTheme.colors.danger,
                )
            },
        ),
        emptyTitle = str(S.desktop_docdist_no_recipients_yet),
        emptyMessage = str(S.desktop_docdist_add_recipients_hint),
    )
}

/** The parsed CSV, rows tagged valid / duplicate / invalid, awaiting "Add". */
@Composable
private fun CsvImportDialog(csv: CsvImportState?, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    val count = csv?.importable?.size ?: 0
    ZillitDialogShell(
        title = str(S.dd_csv_import_title),
        subtitle = csv?.fileName?.let { str(S.desktop_docdist_csv_from_file, it) },
        visible = csv != null,
        onDismiss = { onEvent(DocDistEvent.CancelCsv) },
        icon = ZillitIcons.Upload,
        width = 600.dp,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(DocDistEvent.CancelCsv) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (count > 0) plural(
                    count,
                    S.desktop_docdist_add_one_contact,
                    S.dd_csv_add_n,
                ) else str(S.dd_csv_add_none),
                onClick = { onEvent(DocDistEvent.ConfirmCsv) },
                enabled = count > 0,
            )
        },
    ) {
        csv?.rows?.forEach { row -> CsvRow(row) }
        ZillitText(
            text = str(S.desktop_docdist_csv_import_hint),
            style = ZillitTheme.typography.bodySmall,
            color = c.textMuted,
        )
    }
}

@Composable
private fun CsvRow(row: CsvContact) {
    val c = ZillitTheme.colors
    val (icon, tint, badge) = when {
        !row.valid -> Triple(ZillitIcons.Close, c.danger, str(S.desktop_docdist_csv_invalid_email))
        row.duplicate -> Triple(ZillitIcons.Check, c.warning, str(S.dd_csv_status_duplicate))
        else -> Triple(ZillitIcons.Check, c.success, null)
    }
    HoverRow(padding = ZillitTheme.spacing.sm) {
        ZillitIcon(icon = icon, tint = tint, size = 16.dp)
        Column(Modifier.weight(1f)) {
            if (row.name.isNotBlank()) ZillitText(text = row.name, style = ZillitTheme.typography.label, maxLines = 1)
            ZillitText(
                text = row.email.ifBlank { str(S.desktop_docdist_csv_empty_email) },
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
        title = str(S.dd_create_distribution_list),
        visible = editor != null,
        onDismiss = { onEvent(DocDistEvent.CloseListEditor) },
        icon = ZillitIcons.Users,
        width = 640.dp,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(DocDistEvent.CloseListEditor) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.dd_create_list),
                onClick = { onEvent(DocDistEvent.SaveListEditor) },
                loading = editor?.saving == true,
                enabled = editor?.name?.isNotBlank() == true && editor.recipients.isNotEmpty(),
            )
        },
    ) {
        if (editor == null) return@ZillitDialogShell
        FieldLabel(str(S.desktop_docdist_list_name_required_label))
        ZillitTextField(
            value = editor.name,
            onValueChange = { onEvent(DocDistEvent.EditListEditor(name = it)) },
            placeholder = str(S.desktop_docdist_list_name_example),
        )
        FieldLabel(str(S.description))
        ZillitTextField(
            value = editor.description,
            onValueChange = { onEvent(DocDistEvent.EditListEditor(description = it)) },
            placeholder = str(S.desktop_docdist_what_this_list_is_for),
        )
        FieldLabel(str(S.docusign_section_add_recipients))
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
                text = str(S.dd_import_from_csv),
                onClick = { onEvent(DocDistEvent.PickCsvForListEditor) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.File,
            )
            ZillitButton(
                text = str(S.dd_csv_download_template),
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
