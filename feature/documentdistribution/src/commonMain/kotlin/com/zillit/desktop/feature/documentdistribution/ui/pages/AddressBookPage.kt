package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.permissions.gatedClick
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.HtmlText
import com.zillit.desktop.feature.documentdistribution.domain.RecipientKind
import com.zillit.desktop.feature.documentdistribution.domain.SendStatus
import com.zillit.desktop.feature.documentdistribution.domain.formatBytes
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.plural

/**
 * The address book — the web's `AddressBookModal`: every recipient the
 * production has ever seen down the left, and for the selected one the
 * lists they are on and the emails they were sent.
 */
@Composable
fun AddressBookPage(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    FixedPage {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            Sidebar(state, onEvent, Modifier.width(SIDEBAR_WIDTH.dp).fillMaxSize())
            Box(
                Modifier.weight(1f).fillMaxSize().clip(ZillitTheme.shapes.large).background(c.surface).border(
                    0.5.dp,
                    c.border,
                    ZillitTheme.shapes.large,
                ),
            ) {
                val selected = state.selectedContact
                if (selected == null) {
                    ZillitEmptyState(title = str(S.desktop_docdist_select_a_contact), icon = ZillitIcons.User)
                } else {
                    ContactDetail(selected, state, onEvent)
                }
            }
        }
    }
    ContactEditorDialog(state, onEvent)
    EmailViewDialog(state, onEvent)
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // One screen section, one branch per state.
@Composable
private fun Sidebar(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit, modifier: Modifier) {
    val c = ZillitTheme.colors
    val canPost = state.viewer.canPost
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Column {
            ZillitText(text = str(S.desktop_docdist_address_book_title), style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = str(
                    S.desktop_docdist_x_across_y,
                    plural(state.contacts.size, S.desktop_contact_count_one, S.desktop_contact_count_other),
                    plural(
                        state.lists.size,
                        S.desktop_docdist_one_distribution_list,
                        S.desktop_docdist_distribution_lists_count,
                    ),
                ),
                style = ZillitTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }
        ZillitSearchField(
            value = state.contactsSearch,
            onValueChange = { onEvent(DocDistEvent.SearchContacts(it)) },
            // Names all four filters without the verb, which overflows the sidebar.
            placeholder = str(S.desktop_docdist_search_contacts_departments),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitButton(
                text = str(S.dd_add_contact),
                onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.OpenAddContact) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.UserPlus,
                modifier = Modifier.weight(1f),
            )
            val exportHint = str(
                if (state.contacts.isEmpty()) {
                    S.dd_address_export_no_contacts
                } else {
                    S.dd_address_export_csv_a11y
                },
            )
            ZillitTooltip(exportHint) {
                ZillitButton(
                    text = str(S.dd_address_export_csv),
                    onClick = { onEvent(DocDistEvent.ExportContactsCsv) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Download,
                    enabled = state.contacts.isNotEmpty() && !state.loading,
                )
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(ZillitTheme.shapes.large).background(c.surface).border(
                0.5.dp,
                c.border,
                ZillitTheme.shapes.large,
            ),
        ) {
            when {
                state.loading && state.contacts.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
                state.visibleContacts.isEmpty() -> ZillitEmptyState(
                    title = str(if (state.contactsSearch.isNotBlank()) S.dm_picker_empty else S.no_contacts_yet),
                    message = if (state.contactsSearch.isNotBlank()) {
                        null
                    } else {
                        str(S.desktop_docdist_addresses_remembered_here)
                    },
                    icon = ZillitIcons.Users,
                )
                else -> ZillitLazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.xs),
                ) {
                    items(state.visibleContacts, key = { it.email }) { contact ->
                        val active = contact.email.equals(state.selectedContactEmail, ignoreCase = true)
                        HoverRow(
                            selected = active,
                            onClick = { onEvent(DocDistEvent.SelectContact(contact.email)) },
                            padding = ZillitTheme.spacing.sm,
                        ) {
                            ZillitAvatar(name = contact.displayName, size = 32.dp)
                            Column(Modifier.weight(1f)) {
                                ZillitText(
                                    text = contact.displayName,
                                    style = ZillitTheme.typography.label,
                                    maxLines = 1,
                                )
                                if (contact.name.isNotBlank()) ZillitText(
                                    text = contact.email,
                                    style = ZillitTheme.typography.bodySmall,
                                    color = c.textMuted,
                                    maxLines = 1,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                                    // Shown so a department search explains its own hits.
                                    if (contact.jobTitle.isNotBlank()) ZillitStatusPill(
                                        label = contact.jobTitle,
                                        tone = StatusTone.Neutral,
                                    )
                                    if (contact.lists.isNotEmpty()) ZillitStatusPill(
                                        label = plural(contact.lists.size, S.desktop_docdist_one_list, S.dd_n_lists),
                                        tone = StatusTone.Pending,
                                    )
                                    if (contact.usageCount > 0) ZillitStatusPill(
                                        label = plural(
                                            contact.usageCount,
                                            S.desktop_docdist_one_use,
                                            S.desktop_docdist_uses_count,
                                        ),
                                        tone = StatusTone.Neutral,
                                    )
                                }
                            }
                            ZillitTooltip(
                                if (canPost) str(S.desktop_docdist_email_named, contact.displayName)
                                else str(S.desktop_no_posting_rights),
                            ) {
                                ZillitIconButton(
                                    ZillitIcons.Send,
                                    str(S.desktop_docdist_email_named, contact.displayName),
                                    gatedClick(canPost, { onEvent(askPost) }) { onEvent(
                                        DocDistEvent.ComposeTo(contact.email),
                                    ) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // One screen section, one branch per state.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactDetail(contact: Contact, state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    val canPost = state.viewer.canPost
    var addMenu by remember { mutableStateOf(false) }
    val otherLists = state.lists.filter { list -> contact.lists.none { it.id == list.id } }
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            ZillitAvatar(name = contact.displayName, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                ZillitText(text = contact.displayName, style = ZillitTheme.typography.titleLarge, maxLines = 1)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    ZillitIcon(icon = ZillitIcons.Mail, tint = c.textMuted, size = 14.dp)
                    ZillitText(text = contact.email, color = c.accentText)
                }
                if (contact.jobTitle.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    ) {
                        ZillitIcon(icon = ZillitIcons.User, tint = c.textMuted, size = 14.dp)
                        ZillitText(text = contact.jobTitle, color = c.textSecondary)
                    }
                }
                ZillitText(
                    text = plural(contact.usageCount, S.desktop_docdist_used_in_one, S.dd_used_in),
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitTooltip(if (canPost) str(S.dd_edit_contact) else str(S.desktop_no_posting_rights)) {
                    ZillitIconButton(
                        ZillitIcons.Edit,
                        str(S.dd_edit_contact),
                        gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.OpenEditContact) },
                    )
                }
                ZillitTooltip(str(S.dd_copy_email)) { ZillitIconButton(
                    ZillitIcons.Link,
                    str(S.dd_copy_email),
                    { onEvent(DocDistEvent.CopyContactEmail) },
                ) }
                ZillitTooltip(if (canPost) str(S.dd_delete_contact) else str(S.desktop_no_posting_rights)) {
                    ZillitIconButton(
                        ZillitIcons.Trash,
                        str(S.dd_delete_contact),
                        gatedClick(canPost, { onEvent(askPost) }) { onEvent(
                            DocDistEvent.ConfirmDeleteContact(contact.email),
                        ) },
                        tint = c.danger,
                    )
                }
                ZillitButton(
                    text = str(S.dd_send_email),
                    onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(
                        DocDistEvent.ComposeTo(contact.email),
                    ) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Send,
                )
            }
        }
        ZillitDivider()
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            FieldLabel(str(S.desktop_docdist_lists_with_count, contact.lists.size))
            if (contact.lists.isEmpty()) {
                ZillitText(
                    text = str(S.desktop_docdist_not_in_any_list),
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    contact.lists.forEach { list ->
                        val busy = state.contactListBusyId == list.id
                        Chip(
                            label = list.name,
                            tone = StatusTone.Pending,
                            onRemove = if (canPost && !busy) {
                                { onEvent(DocDistEvent.RemoveContactFromList(list.id)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            if (otherLists.isNotEmpty()) {
                Box {
                    ZillitButton(
                        text = str(S.desktop_docdist_add_to_list),
                        onClick = gatedClick(canPost, { onEvent(askPost) }) { addMenu = true },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                        enabled = state.contactListBusyId == null,
                        loading = state.contactListBusyId != null,
                    )
                    MenuPopup(
                        expanded = addMenu,
                        onDismiss = { addMenu = false },
                        entries = otherLists.map { list -> MenuEntry(
                            "${list.name} (${list.recipients.size})",
                            { onEvent(DocDistEvent.AddContactToList(list.id)) },
                            ZillitIcons.Users,
                        ) },
                        width = 280.dp,
                    )
                }
            }
        }
        val sent = remember(contact.email, state.contactDistributions) { sentTo(contact, state.contactDistributions) }
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            FieldLabel(str(S.desktop_docdist_sent_emails_with_count, sent.size))
            when {
                state.loadingContactEmails && sent.isEmpty() -> ZillitSpinner(size = 16.dp)
                sent.isEmpty() -> ZillitText(
                    text = str(S.dd_sent_emails_empty),
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textMuted,
                )
                else -> sent.forEach { (dist, kind) -> SentEmailRow(dist, kind, onEvent) }
            }
        }
    }
}

/** The sends this contact received, newest first, tagged with the line they were on. */
private fun sentTo(contact: Contact, sends: List<Distribution>): List<Pair<Distribution, RecipientKind>> {
    val email = contact.email.lowercase()
    return sends.mapNotNull { d ->
        val row = d.recipients.firstOrNull { it.recipient.email.lowercase() == email } ?: return@mapNotNull null
        d to row.kind
    }.sortedByDescending { it.first.sentAt ?: 0 }
}

@Composable
private fun SentEmailRow(dist: Distribution, kind: RecipientKind, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    HoverRow(onClick = { onEvent(DocDistEvent.ViewEmail(dist.id)) }, padding = ZillitTheme.spacing.sm) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) { ZillitIcon(
            icon = ZillitIcons.Mail,
            tint = c.accent,
        ) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    text = dist.subject,
                    style = ZillitTheme.typography.label,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                ZillitText(
                    text = EpochDate.date(dist.sentAt),
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
            val snippet = HtmlText.snippet(dist.bodyHtml)
            if (snippet.isNotBlank()) ZillitText(
                text = snippet,
                style = ZillitTheme.typography.bodySmall,
                color = c.textSecondary,
                maxLines = 1,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val status = dist.status.takeIf { it != SendStatus.Unknown } ?: SendStatus.Sent
                ZillitStatusPill(label = status.label, tone = status.tone(), dot = true)
                if (kind != RecipientKind.To) ZillitStatusPill(label = kind.label, tone = StatusTone.Neutral)
                if (dist.attachments.isNotEmpty()) {
                    ZillitIcon(icon = ZillitIcons.Paperclip, tint = c.textMuted, size = 12.dp)
                    ZillitText(
                        text = "${dist.attachments.size}",
                        style = ZillitTheme.typography.bodySmall,
                        color = c.textMuted,
                    )
                }
            }
        }
    }
}

@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
private fun ContactEditorDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val editor = state.contactEditor
    val c = ZillitTheme.colors
    ZillitDialogShell(
        title = str(if (editor?.isNew == false) S.dd_edit_contact else S.dd_add_contact),
        visible = editor != null,
        onDismiss = { onEvent(DocDistEvent.CloseContactEditor) },
        icon = ZillitIcons.UserPlus,
        width = 480.dp,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(DocDistEvent.CloseContactEditor) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(if (editor?.isNew == false) S.dd_action_save_changes else S.dd_add_contact),
                onClick = { onEvent(DocDistEvent.SaveContactEditor) },
                enabled = editor != null && editor.emailProblem(state.contacts) == null && !editor.saving,
                loading = editor?.saving == true,
            )
        },
    ) {
        if (editor == null) return@ZillitDialogShell
        val emailError = editor.shownEmailError(state.contacts)
        var emailFocused by remember { mutableStateOf(false) }
        FieldLabel(str(S.docusign_add_contact_email_label))
        ZillitTextField(
            value = editor.email,
            onValueChange = { onEvent(DocDistEvent.EditContact(email = it)) },
            placeholder = str(S.docusign_add_contact_email_hint),
            errorText = emailError,
            onImeAction = { onEvent(DocDistEvent.SaveContactEditor) },
            // Leaving the field is what lets its error show.
            modifier = Modifier.onFocusChanged { focus ->
                if (emailFocused && !focus.hasFocus) onEvent(DocDistEvent.TouchContactEmail)
                emailFocused = focus.hasFocus
            },
        )
        // Guidance, not a problem — it waits until the field is clean.
        if (emailError == null && editor.emailChanged) {
            ZillitText(
                text = str(S.desktop_docdist_email_change_hint),
                style = ZillitTheme.typography.bodySmall,
                color = c.textMuted,
            )
        }
        FieldLabel(str(S.name))
        ZillitTextField(
            value = editor.name,
            onValueChange = { onEvent(DocDistEvent.EditContact(name = it)) },
            placeholder = str(S.docusign_add_contact_name_hint),
            onImeAction = { onEvent(DocDistEvent.SaveContactEditor) },
        )
        DepartmentField(editor.job, state.departments, onEvent)
        FieldLabel(str(S.desktop_docdist_lists_optional))
        val byId = state.lists.associateBy { it.id }
        ZillitMultiSelect(
            selected = editor.listIds.mapNotNull { byId[it] },
            options = state.lists,
            label = DistributionList::name,
            onChange = { chosen -> onEvent(DocDistEvent.EditContact(listIds = chosen.map { it.id })) },
            placeholder = str(S.desktop_docdist_assign_to_lists),
            emptyText = str(S.dd_no_lists),
        )
    }
}

/**
 * ZL-21622: "Job" became "Department". It suggests the project's departments
 * as you type but saves whatever is typed — a vendor's department may never
 * have been set up on the project. `job` stays the wire field.
 */
@Composable
private fun DepartmentField(value: String, departments: List<String>, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    var focused by remember { mutableStateOf(false) }
    FieldLabel(str(S.department))
    ZillitTextField(
        value = value,
        onValueChange = { onEvent(DocDistEvent.EditContact(job = it)) },
        placeholder = str(S.desktop_docdist_department_placeholder),
        onImeAction = { onEvent(DocDistEvent.SaveContactEditor) },
        modifier = Modifier.onFocusChanged { focused = it.hasFocus },
    )
    val typed = value.trim().lowercase()
    val matches = departments
        .filter { typed.isEmpty() || it.lowercase().contains(typed) }
        .filterNot { it.lowercase() == typed }
        .take(DEPARTMENT_SUGGESTIONS)
    if (focused && matches.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(c.surfaceRaised)
                .border(0.5.dp, c.border, ZillitTheme.shapes.medium)
                .padding(ZillitTheme.spacing.xs),
        ) {
            matches.forEach { department ->
                HoverRow(
                    onClick = { onEvent(DocDistEvent.EditContact(job = department)) },
                    padding = ZillitTheme.spacing.sm,
                ) {
                    ZillitText(text = department, style = ZillitTheme.typography.label, maxLines = 1)
                }
            }
        }
    }
    ZillitText(
        text = str(S.desktop_docdist_department_hint),
        style = ZillitTheme.typography.bodySmall,
        color = c.textMuted,
    )
}

private const val DEPARTMENT_SUGGESTIONS = 6

/** A sent email read back — subject, recipients, body, attachments — with Duplicate. */
@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
private fun EmailViewDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val d = state.viewingEmail
    val c = ZillitTheme.colors
    val canPost = state.viewer.canPost
    ZillitDialogShell(
        title = d?.subject.orEmpty(),
        subtitle = d?.let {
            EpochDate.dateTime(it.sentAt).ifBlank { "—" } +
                if (it.status != SendStatus.Unknown) " · ${it.status.label}" else ""
        },
        visible = d != null,
        onDismiss = { onEvent(DocDistEvent.ViewEmail(null)) },
        icon = ZillitIcons.Mail,
        width = 680.dp,
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(DocDistEvent.ViewEmail(null)) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.dd_action_duplicate),
                onClick = gatedClick(canPost, { onEvent(askPost) }) {
                    d?.let { onEvent(DocDistEvent.DuplicateDistribution(it.id)) }
                },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Send,
            )
        },
    ) {
        if (d == null) return@ZillitDialogShell
        RecipientKind.entries.forEach { kind ->
            val rows = d.recipientsOf(kind)
            if (rows.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    ZillitText(
                        text = kind.label,
                        style = ZillitTheme.typography.label,
                        color = c.textMuted,
                        modifier = Modifier.width(40.dp),
                    )
                    ZillitText(
                        text = rows.joinToString(", ") { it.recipient.display },
                        style = ZillitTheme.typography.bodySmall,
                    )
                }
            }
        }
        ZillitDivider()
        val body = HtmlText.toPlainText(d.bodyHtml)
        if (body.isBlank()) ZillitText(text = str(S.dd_sent_emails_empty_body), color = c.textMuted) else ZillitText(
            text = body,
        )
        if (d.attachments.isNotEmpty()) {
            ZillitDivider()
            FieldLabel(plural(d.attachments.size, S.desktop_docdist_one_attachment, S.dd_attachments_line))
            d.attachments.forEach { a ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    FileGlyph(a.name, a.contentType, size = 26.dp)
                    ZillitText(text = a.name, maxLines = 1, modifier = Modifier.weight(1f))
                    if (a.sizeBytes > 0) ZillitText(
                        text = formatBytes(a.sizeBytes),
                        style = ZillitTheme.typography.bodySmall,
                        color = c.textMuted,
                    )
                }
            }
        }
    }
}

private const val SIDEBAR_WIDTH = 340
