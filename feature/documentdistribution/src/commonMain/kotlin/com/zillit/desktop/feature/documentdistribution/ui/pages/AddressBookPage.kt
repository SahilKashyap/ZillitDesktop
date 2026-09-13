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
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.HtmlText
import com.zillit.desktop.feature.documentdistribution.domain.RecipientKind
import com.zillit.desktop.feature.documentdistribution.domain.SendStatus
import com.zillit.desktop.feature.documentdistribution.domain.formatBytes
import com.zillit.desktop.feature.documentdistribution.domain.isValidEmail
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState

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
                    ZillitEmptyState(title = "Select a contact", icon = ZillitIcons.User)
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
            ZillitText(text = "Address book", style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = "${state.contacts.size} contact" + (if (state.contacts.size == 1) "" else "s") +
                    " across ${state.lists.size} distribution list" + if (state.lists.size == 1) "" else "s",
                style = ZillitTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }
        ZillitSearchField(
            value = state.contactsSearch,
            onValueChange = { onEvent(DocDistEvent.SearchContacts(it)) },
            placeholder = "Search name, email, or list",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitButton(
                text = "Add contact",
                onClick = gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.OpenAddContact) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.UserPlus,
                modifier = Modifier.weight(1f),
            )
            ZillitTooltip(if (state.contacts.isEmpty()) "No contacts to export" else "Export all contacts as CSV") {
                ZillitButton(
                    text = "Export CSV",
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
                    title = if (state.contactsSearch.isNotBlank()) "No matches" else "No contacts yet",
                    message = if (state.contactsSearch.isNotBlank()) {
                        null
                    } else {
                        "Addresses used in a distribution are remembered here."
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
                                    if (contact.lists.isNotEmpty()) ZillitStatusPill(
                                        label = "${contact.lists.size} list" + if (contact.lists.size == 1) "" else "s",
                                        tone = StatusTone.Pending,
                                    )
                                    if (contact.usageCount > 0) ZillitStatusPill(
                                        label = "${contact.usageCount} use" + if (contact.usageCount == 1) "" else "s",
                                        tone = StatusTone.Neutral,
                                    )
                                }
                            }
                            ZillitTooltip(if (canPost) "Email ${contact.displayName}" else "No posting rights") {
                                ZillitIconButton(
                                    ZillitIcons.Send,
                                    "Email ${contact.displayName}",
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
                val plural = if (contact.usageCount == 1) "" else "s"
                ZillitText(
                    text = "Used in ${contact.usageCount} email$plural / list$plural",
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitTooltip(if (canPost) "Edit contact" else "No posting rights") {
                    ZillitIconButton(
                        ZillitIcons.Edit,
                        "Edit contact",
                        gatedClick(canPost, { onEvent(askPost) }) { onEvent(DocDistEvent.OpenEditContact) },
                    )
                }
                ZillitTooltip("Copy email") { ZillitIconButton(
                    ZillitIcons.Link,
                    "Copy email",
                    { onEvent(DocDistEvent.CopyContactEmail) },
                ) }
                ZillitTooltip(if (canPost) "Delete contact" else "No posting rights") {
                    ZillitIconButton(
                        ZillitIcons.Trash,
                        "Delete contact",
                        gatedClick(canPost, { onEvent(askPost) }) { onEvent(
                            DocDistEvent.ConfirmDeleteContact(contact.email),
                        ) },
                        tint = c.danger,
                    )
                }
                ZillitButton(
                    text = "Send email",
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
            FieldLabel("Distribution lists · ${contact.lists.size}")
            if (contact.lists.isEmpty()) {
                ZillitText(
                    text = "Not part of any distribution list yet.",
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
                        text = "Add to list…",
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
            FieldLabel("Sent emails · ${sent.size}")
            when {
                state.loadingContactEmails && sent.isEmpty() -> ZillitSpinner(size = 16.dp)
                sent.isEmpty() -> ZillitText(
                    text = "No emails sent to this contact yet.",
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
        title = if (editor?.isNew == false) "Edit contact" else "Add contact",
        visible = editor != null,
        onDismiss = { onEvent(DocDistEvent.CloseContactEditor) },
        icon = ZillitIcons.UserPlus,
        width = 480.dp,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.CloseContactEditor) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (editor?.isNew == false) "Save changes" else "Add contact",
                onClick = { onEvent(DocDistEvent.SaveContactEditor) },
                enabled = editor != null && isValidEmail(editor.email) && !editor.saving,
                loading = editor?.saving == true,
            )
        },
    ) {
        if (editor == null) return@ZillitDialogShell
        FieldLabel("Email *")
        ZillitTextField(
            value = editor.email,
            onValueChange = { onEvent(DocDistEvent.EditContact(email = it)) },
            placeholder = "name@example.com",
            onImeAction = { onEvent(DocDistEvent.SaveContactEditor) },
        )
        if (editor.emailChanged) {
            ZillitText(
                text = "Changing the email moves this contact across your distribution lists. " +
                    "Past sent emails keep the old address.",
                style = ZillitTheme.typography.bodySmall,
                color = c.textMuted,
            )
        }
        FieldLabel("Name")
        ZillitTextField(
            value = editor.name,
            onValueChange = { onEvent(DocDistEvent.EditContact(name = it)) },
            placeholder = "Full name",
            onImeAction = { onEvent(DocDistEvent.SaveContactEditor) },
        )
        FieldLabel("Job")
        ZillitTextField(
            value = editor.job,
            onValueChange = { onEvent(DocDistEvent.EditContact(job = it)) },
            placeholder = "Role / title",
            onImeAction = { onEvent(DocDistEvent.SaveContactEditor) },
        )
        FieldLabel("Distribution lists (optional)")
        val byId = state.lists.associateBy { it.id }
        ZillitMultiSelect(
            selected = editor.listIds.mapNotNull { byId[it] },
            options = state.lists,
            label = DistributionList::name,
            onChange = { chosen -> onEvent(DocDistEvent.EditContact(listIds = chosen.map { it.id })) },
            placeholder = "Assign to one or more lists",
            emptyText = "No distribution lists yet",
        )
    }
}

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
                text = "Close",
                onClick = { onEvent(DocDistEvent.ViewEmail(null)) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Duplicate",
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
        if (body.isBlank()) ZillitText(text = "This email has no body", color = c.textMuted) else ZillitText(
            text = body,
        )
        if (d.attachments.isNotEmpty()) {
            ZillitDivider()
            FieldLabel("${d.attachments.size} attachment" + if (d.attachments.size == 1) "" else "s")
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
