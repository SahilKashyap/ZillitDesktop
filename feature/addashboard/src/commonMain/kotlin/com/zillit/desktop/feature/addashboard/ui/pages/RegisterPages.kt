package com.zillit.desktop.feature.addashboard.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.Agency
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteCategory
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.domain.EngagementType
import com.zillit.desktop.feature.addashboard.domain.ImportProblem
import com.zillit.desktop.feature.addashboard.domain.ImportRow
import com.zillit.desktop.feature.addashboard.ui.AdDestination
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdUiState
import com.zillit.desktop.feature.addashboard.ui.BankField
import com.zillit.desktop.feature.addashboard.ui.FormEvent
import com.zillit.desktop.feature.addashboard.ui.ImportEvent
import com.zillit.desktop.feature.addashboard.ui.ImportField
import com.zillit.desktop.feature.addashboard.ui.RegisterEvent

/**
 * The Artiste Register: every artiste the production has, however they got here.
 *
 * A row's unread dot is **per artiste**, and opening the row reads it — not the
 * tab, which would clear every dot at once and leave the sidebar's rollup
 * counting nothing.
 */
@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
internal fun RegisterPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    AdPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.register.search,
                onValueChange = { onEvent(RegisterEvent.Search(it)) },
                modifier = Modifier.width(SEARCH_WIDTH),
                placeholder = str(S.desktop_ad_search_register_placeholder),
            )
            ZillitSelect(
                value = state.register.status,
                options = listOf(null) + ArtisteStatus.entries,
                onSelect = { onEvent(RegisterEvent.FilterStatus(it)) },
                label = { it?.label ?: str(S.desktop_ad_all_onboarding) },
                modifier = Modifier.width(FILTER_WIDTH),
            )
            ZillitSelect(
                value = state.register.engagement,
                options = listOf(null) + EngagementType.entries,
                onSelect = { onEvent(RegisterEvent.FilterEngagement(it)) },
                label = { it?.label ?: str(S.desktop_ad_all_engagements) },
                modifier = Modifier.width(FILTER_WIDTH),
            )
            ZillitChoiceChip(
                label = str(S.desktop_ad_union_only),
                selected = state.register.unionOnly,
                onClick = { onEvent(RegisterEvent.UnionOnly(!state.register.unionOnly)) },
            )
            if (state.viewer.canPost) {
                ZillitButton(
                    text = str(S.desktop_ad_add_direct_extra),
                    onClick = { onEvent(FormEvent.Open(null)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.UserPlus,
                )
                ZillitButton(
                    text = str(S.desktop_ad_upload_agency_list),
                    onClick = { onEvent(AdEvent.Open(AdDestination.Onboarding)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Upload,
                )
            }
        }

        ZillitSectionCard(
            title = str(S.desktop_ad_artiste_register),
            meta = str(S.desktop_ad_n_artistes, state.registerRows.size),
            padded = false,
        ) {
            ZillitDataTable(
                rows = state.registerRows,
                columns = registerColumns(state, onEvent),
                key = { it.id },
                onRowClick = { onEvent(RegisterEvent.OpenArtiste(it.id)) },
                emptyTitle = str(S.desktop_ad_no_artistes_yet),
                emptyMessage = str(S.desktop_ad_no_artistes_yet_message),
                loading = state.loading && state.artistes.isEmpty(),
                virtualised = false,
            )
        }
    }

    DeleteArtisteDialog(state, onEvent)
}

@Suppress("MagicNumber") // Column weights, which read better as the ratios they are.
private fun registerColumns(
    state: AdUiState,
    onEvent: (AdEvent) -> Unit,
): List<TableColumn<Artiste>> = listOf(
    TableColumn(str(S.name), width = ColumnWidth.Weight(2f)) { artiste ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(text = artiste.name, style = ZillitTheme.typography.bodyMedium)
            // Per-artiste unread: the register badges by row, never by tab.
            ZillitBadge(count = state.artisteUnread(artiste.id))
        }
    },
    textColumn(str(S.desktop_reference)) { it.refNumber },
    textColumn(str(S.av_category)) { it.category.label },
    textColumn(str(S.desktop_engagement)) { it.engagement.label },
    textColumn(str(S.desktop_dm_agency)) { it.agencyName },
    textColumn(str(S.dm_label_union)) { it.union },
    TableColumn(str(S.status), width = ColumnWidth.Fixed(STATUS_WIDTH)) { artiste ->
        ZillitStatusPill(label = artiste.status.label, tone = artiste.status.tone())
    },
    TableColumn(str(S.dd_actions), width = ColumnWidth.Fixed(ACTION_WIDTH)) { artiste ->
        if (!state.viewer.canPost) return@TableColumn
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitIconButton(
                icon = ZillitIcons.Edit,
                contentDescription = str(S.edit),
                onClick = { onEvent(FormEvent.Open(artiste.id)) },
            )
            // A verified artiste is not deletable: they have been through
            // onboarding and may already carry signed days.
            if (artiste.deletable) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.delete),
                    onClick = { onEvent(RegisterEvent.AskDelete(artiste)) },
                )
            }
        }
    },
)

/** One artiste's page: their details, and the three things an AD does to them. */
@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
internal fun ArtisteViewPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val open = state.artisteDetail ?: return
    val artiste = open.artiste
    AdPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = str(S.back),
                onClick = { onEvent(RegisterEvent.CloseArtiste) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
            ZillitText(text = artiste.name, style = ZillitTheme.typography.titleMedium)
            ZillitStatusPill(label = artiste.status.label, tone = artiste.status.tone())
        }

        if (state.viewer.canPost) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                if (artiste.status != ArtisteStatus.Verified) {
                    ZillitButton(
                        text = str(S.desktop_ad_verify_artiste),
                        onClick = { onEvent(RegisterEvent.Verify(artiste.id)) },
                        variant = ButtonVariant.Secondary,
                        leadingIcon = ZillitIcons.Tick,
                        loading = open.busy,
                    )
                }
                if (artiste.blocked) {
                    ZillitButton(
                        text = str(S.desktop_unblock),
                        onClick = { onEvent(RegisterEvent.Unblock(artiste.id)) },
                        variant = ButtonVariant.Secondary,
                        loading = open.busy,
                    )
                } else {
                    ZillitButton(
                        text = str(S.block),
                        onClick = { onEvent(RegisterEvent.AskBlock) },
                        variant = ButtonVariant.Tertiary,
                        leadingIcon = ZillitIcons.Shield,
                    )
                }
                // Only an external artiste has a portal link at all: an
                // internal one signs in the Supporting Artistes tool with
                // their own account.
                if (artiste.portalLinkable) {
                    ZillitButton(
                        text = str(S.desktop_ad_copy_portal_link),
                        onClick = { onEvent(RegisterEvent.CopyPortalLink) },
                        variant = ButtonVariant.Tertiary,
                        leadingIcon = ZillitIcons.Link,
                        loading = open.busy,
                    )
                }
                ZillitButton(
                    text = str(S.edit),
                    onClick = { onEvent(FormEvent.Open(artiste.id)) },
                    variant = ButtonVariant.Tertiary,
                    leadingIcon = ZillitIcons.Edit,
                )
            }
        }

        if (open.portalLink.isNotBlank()) {
            ZillitNotice(
                text = open.portalLink,
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Link,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ZillitSectionCard(title = str(S.desktop_ad_artiste_details)) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                AdDetailRow(label = str(S.desktop_reference), value = artiste.refNumber)
                AdDetailRow(label = str(S.email), value = artiste.email)
                AdDetailRow(label = str(S.phone), value = artiste.phone)
                AdDetailRow(label = str(S.av_category), value = artiste.category.label)
                AdDetailRow(label = str(S.desktop_engagement), value = artiste.engagement.label)
                AdDetailRow(label = str(S.dm_label_union), value = artiste.union)
                AdDetailRow(label = str(S.desktop_dm_agency), value = artiste.agencyName)
                AdDetailRow(label = str(S.desktop_ad_date_of_birth), value = artiste.dateOfBirth)
                AdDetailRow(label = str(S.address), value = artiste.address)
                if (artiste.isMinor) {
                    AdDetailRow(label = str(S.desktop_ad_minor), value = str(S.yes))
                }
            }
        }

        ZillitSectionCard(title = str(S.dm_checklist_bank)) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                AdDetailRow(label = str(S.desktop_account_name), value = artiste.bank.accountName)
                AdDetailRow(label = str(S.desktop_ad_account_number), value = artiste.bank.accountNumber)
                AdDetailRow(label = str(S.desktop_ad_sort_code), value = artiste.bank.sortCode)
                AdDetailRow(label = str(S.ah_lbl_iban_row), value = artiste.bank.ibanNumber)
                if (!artiste.bank.hasNumber) {
                    // An IBAN alone satisfies this, so the warning is only for
                    // an artiste who has given neither.
                    ZillitText(
                        text = str(S.desktop_ad_no_bank_number),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.warning,
                    )
                }
            }
        }

        if (artiste.documents.isNotEmpty()) {
            ZillitSectionCard(title = str(S.txt_documents), padded = false) {
                ZillitDataTable(
                    rows = artiste.documents,
                    columns = listOf(
                        textColumn(str(S.document), width = ColumnWidth.Weight(2f)) { it.label },
                        textColumn(str(S.description)) { it.description },
                        TableColumn(str(S.status), width = ColumnWidth.Fixed(STATUS_WIDTH)) { document ->
                            ZillitStatusPill(
                                label = if (document.uploaded) str(S.sides_uploaded) else str(S.pending),
                                tone = if (document.uploaded) StatusTone.Done else StatusTone.Pending,
                            )
                        },
                    ),
                    key = { it.id + it.label },
                    virtualised = false,
                )
            }
        }
    }

    BlockArtisteDialog(state, onEvent)
}

/**
 * The artiste form — Manual Entry, and editing one that exists.
 *
 * Only identity is required: a project user from the eligible list, or the
 * external toggle with a first and last name. The e-mail is format-checked but
 * not demanded, which is the web's rule — and its known weakness, since an
 * external artiste with no address has a dead portal link until somebody adds
 * one.
 */
@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
internal fun ArtisteFormPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val form = state.artisteForm ?: return
    val draft = form.draft
    AdPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(FormEvent.Close) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
            ZillitText(
                text = if (form.isEdit) str(S.desktop_ad_edit_artiste) else str(S.desktop_ad_manual_entry),
                style = ZillitTheme.typography.titleMedium,
            )
        }

        form.error?.let { message ->
            ZillitNotice(
                text = message,
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ZillitSectionCard(title = str(S.av_section_identity)) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitSwitch(
                        checked = draft.isExternal,
                        onCheckedChange = { onEvent(FormEvent.External(it)) },
                    )
                    ZillitText(text = str(S.desktop_ad_external_artiste), style = ZillitTheme.typography.bodySmall)
                }
                if (draft.isExternal) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                        ZillitTextField(
                            value = draft.firstName,
                            onValueChange = { onEvent(FormEvent.FirstName(it)) },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            label = str(S.first_name_label),
                        )
                        ZillitTextField(
                            value = draft.lastName,
                            onValueChange = { onEvent(FormEvent.LastName(it)) },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            label = str(S.last_name_label),
                        )
                    }
                } else {
                    // Exactly three groups: the Stand-Ins and Stunts
                    // departments, plus the Supporting Cast designation.
                    // Anyone else is onboarded with the external toggle.
                    ZillitSearchSelect(
                        value = form.eligible.firstOrNull { it.userId == draft.userId },
                        options = form.eligible,
                        onSelect = { onEvent(FormEvent.PickCrew(it.userId)) },
                        label = { it.fullName },
                        subtitle = { it.designation.ifBlank { it.department } },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = str(S.desktop_ad_pick_from_crew),
                    )
                }
            }
        }

        ZillitSectionCard(title = str(S.contact)) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = draft.email,
                    onValueChange = { onEvent(FormEvent.Email(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = str(S.email),
                    helperText = if (draft.isExternal) str(S.desktop_ad_email_needed_for_portal) else null,
                )
                ZillitTextField(
                    value = draft.phone,
                    onValueChange = { onEvent(FormEvent.Phone(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = str(S.phone),
                )
                ZillitTextField(
                    value = draft.address,
                    onValueChange = { onEvent(FormEvent.Address(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = str(S.address),
                )
                ZillitTextField(
                    value = draft.dateOfBirth,
                    onValueChange = { onEvent(FormEvent.DateOfBirth(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = str(S.desktop_ad_date_of_birth),
                    placeholder = "1990-01-31",
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitCheckbox(
                        checked = draft.isMinor,
                        onCheckedChange = { onEvent(FormEvent.Minor(it)) },
                        label = str(S.desktop_ad_minor),
                    )
                }
            }
        }

        ZillitSectionCard(title = str(S.desktop_ad_booking)) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitSelect(
                    value = draft.category,
                    options = ArtisteCategory.entries,
                    onSelect = { onEvent(FormEvent.Category(it)) },
                    label = { it.label },
                    modifier = Modifier.fillMaxWidth(),
                )
                ZillitSelect(
                    value = draft.engagement,
                    options = EngagementType.entries,
                    onSelect = { onEvent(FormEvent.Engagement(it)) },
                    label = { it.label },
                    modifier = Modifier.fillMaxWidth(),
                )
                // The agency's **id** is what a purchase order is raised
                // against: an artiste saved with only a name lands in the "no
                // vendor" group and gets no order at all.
                ZillitSearchSelect(
                    value = form.agencies.firstOrNull { it.id == draft.agencyId },
                    options = form.agencies,
                    onSelect = { onEvent(FormEvent.PickAgency(it.id, it.name)) },
                    label = { it.name },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = str(S.desktop_ad_pick_agency),
                    onClear = { onEvent(FormEvent.PickAgency("", "")) },
                )
                ZillitSearchSelect(
                    value = form.unions.firstOrNull { it.label == draft.union },
                    options = form.unions,
                    onSelect = { onEvent(FormEvent.Union(it.label)) },
                    label = { it.label },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = str(S.desktop_ad_pick_union),
                    onClear = { onEvent(FormEvent.Union("")) },
                )
                ZillitTextField(
                    value = draft.refNumber,
                    onValueChange = { onEvent(FormEvent.RefNumber(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = str(S.desktop_reference),
                )
            }
        }

        ZillitSectionCard(title = str(S.dm_checklist_bank)) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = draft.bank.accountName,
                    onValueChange = { onEvent(FormEvent.Bank(BankField.AccountName, it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = str(S.desktop_account_name),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    ZillitTextField(
                        value = draft.bank.accountNumber,
                        onValueChange = { onEvent(FormEvent.Bank(BankField.AccountNumber, it)) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        label = str(S.desktop_ad_account_number),
                    )
                    ZillitTextField(
                        value = draft.bank.sortCode,
                        onValueChange = { onEvent(FormEvent.Bank(BankField.SortCode, it)) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        label = str(S.desktop_ad_sort_code),
                    )
                }
                ZillitTextField(
                    value = draft.bank.ibanNumber,
                    onValueChange = { onEvent(FormEvent.Bank(BankField.Iban, it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = str(S.ah_lbl_iban_row),
                    // An IBAN alone is enough: international artistes often
                    // bank somewhere that quotes no local account number.
                    helperText = str(S.desktop_ad_iban_alone_is_enough),
                )
                ZillitTextField(
                    value = draft.bank.swiftCode,
                    onValueChange = { onEvent(FormEvent.Bank(BankField.Swift, it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = str(S.desktop_payment_swift),
                )
            }
        }

        ZillitSectionCard(
            title = str(S.txt_documents),
            // The AD names what an artiste must upload; the artiste supplies
            // the files through their own portal.
            meta = str(S.desktop_ad_documents_asked_of_artiste),
            action = {
                ZillitButton(
                    text = str(S.add),
                    onClick = { onEvent(FormEvent.AddDocument) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                draft.documents.forEachIndexed { index, document ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitTextField(
                            value = document.label,
                            onValueChange = { onEvent(FormEvent.DocumentLabel(index, it)) },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            placeholder = str(S.dm_nda_document_name_label),
                        )
                        ZillitTextField(
                            value = document.description,
                            onValueChange = { onEvent(FormEvent.DocumentDescription(index, it)) },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            placeholder = str(S.description),
                        )
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = str(S.remove),
                            onClick = { onEvent(FormEvent.RemoveDocument(index)) },
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            // A draft artiste reopened offers **Add artiste**, which promotes
            // the same record to pending rather than creating a second.
            if (form.canPromote) {
                ZillitButton(
                    text = str(S.desktop_ad_add_artiste),
                    onClick = { onEvent(FormEvent.Promote) },
                    loading = form.saving,
                )
            } else {
                ZillitButton(
                    text = str(S.desktop_ad_save_and_add_to_list),
                    onClick = { onEvent(FormEvent.Save(asDraft = false)) },
                    loading = form.saving,
                )
            }
            ZillitButton(
                text = str(S.desktop_ad_save_draft),
                onClick = { onEvent(FormEvent.Save(asDraft = true)) },
                variant = ButtonVariant.Secondary,
                loading = form.saving,
            )
        }
    }
}

/**
 * Artistes Onboarding: an agency's spreadsheet, read and committed.
 *
 * The server parses the file and matches each row's agency name to a
 * registered vendor; the client re-checks names and e-mail addresses so they
 * can be fixed in place, and nothing is saved until a commit.
 */
@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
internal fun OnboardingPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val import = state.import
    AdPage {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitButton(
                text = str(S.desktop_ad_upload_agency_list),
                onClick = { onEvent(ImportEvent.PickFile) },
                leadingIcon = ZillitIcons.Upload,
                loading = import?.uploading == true,
            )
            ZillitButton(
                text = str(S.desktop_ad_manual_entry),
                onClick = { onEvent(FormEvent.Open(null)) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.UserPlus,
            )
            if (import != null) {
                ZillitButton(
                    text = str(S.clear),
                    onClick = { onEvent(ImportEvent.Clear) },
                    variant = ButtonVariant.Tertiary,
                )
            }
        }

        if (import?.preview == null) {
            ZillitEmptyState(
                title = str(S.desktop_ad_upload_to_begin),
                message = str(S.desktop_ad_upload_formats),
                icon = ZillitIcons.Upload,
            )
            return@AdPage
        }

        val preview = import.preview
        val needsReview = preview.needsReview
        if (needsReview.isNotEmpty()) {
            ZillitNotice(
                text = str(S.desktop_ad_n_rows_need_review, needsReview.size),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ZillitSectionCard(
            title = import.fileName,
            meta = str(S.desktop_ad_n_rows_n_matched, preview.totalRows, preview.matchedAgencies),
            padded = false,
            action = {
                ZillitButton(
                    text = str(S.select_all),
                    onClick = { onEvent(ImportEvent.ToggleAll) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            },
        ) {
            ZillitDataTable(
                rows = preview.rows,
                columns = importColumns(onEvent),
                key = { it.index },
                virtualised = false,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitButton(
                text = str(S.desktop_ad_add_n_to_register, import.readyCount),
                onClick = { onEvent(ImportEvent.Commit) },
                enabled = import.readyCount > 0,
                loading = import.committing,
            )
            // Hidden once the day is locked: there is no list to add to.
            if (state.canEditDay) {
                ZillitButton(
                    text = str(S.desktop_ad_add_n_to_todays_list, import.readyCount),
                    onClick = { onEvent(ImportEvent.CommitToday) },
                    variant = ButtonVariant.Secondary,
                    enabled = import.readyCount > 0,
                    loading = import.committing,
                )
            }
        }
    }
}

@Suppress("MagicNumber") // Column weights, which read better as the ratios they are.
private fun importColumns(onEvent: (AdEvent) -> Unit): List<TableColumn<ImportRow>> = listOf(
    TableColumn(str(S.select), width = ColumnWidth.Fixed(CHECK_WIDTH)) { row ->
        ZillitCheckbox(
            checked = row.selected,
            onCheckedChange = { onEvent(ImportEvent.ToggleRow(row.index)) },
            enabled = row.valid,
        )
    },
    TableColumn(str(S.first_name_label), width = ColumnWidth.Weight(1f)) { row ->
        ZillitTextField(
            value = row.firstName,
            onValueChange = { onEvent(ImportEvent.EditRow(row.index, ImportField.FirstName, it)) },
            modifier = Modifier.fillMaxWidth(),
            errorText = if (ImportProblem.MissingFirstName in row.problems) str(S.desktop_csync_required) else null,
        )
    },
    TableColumn(str(S.last_name_label), width = ColumnWidth.Weight(1f)) { row ->
        ZillitTextField(
            value = row.lastName,
            onValueChange = { onEvent(ImportEvent.EditRow(row.index, ImportField.LastName, it)) },
            modifier = Modifier.fillMaxWidth(),
            errorText = if (ImportProblem.MissingLastName in row.problems) str(S.desktop_csync_required) else null,
        )
    },
    TableColumn(str(S.email), width = ColumnWidth.Weight(1.4f)) { row ->
        ZillitTextField(
            value = row.email,
            onValueChange = { onEvent(ImportEvent.EditRow(row.index, ImportField.Email, it)) },
            modifier = Modifier.fillMaxWidth(),
            errorText = if (ImportProblem.InvalidEmail in row.problems) str(S.desktop_ad_email_invalid) else null,
        )
    },
    TableColumn(str(S.desktop_dm_agency), width = ColumnWidth.Weight(1.2f)) { row ->
        Column {
            ZillitText(text = row.agencyName.ifBlank { EM_DASH }, style = ZillitTheme.typography.bodySmall)
            // A matched agency is what makes a purchase order possible; an
            // unmatched name is kept as plain text and gets none.
            ZillitStatusPill(
                label = if (row.agencyMatched) {
                    str(S.desktop_ad_matched_vendor)
                } else {
                    str(S.desktop_ad_name_only)
                },
                tone = if (row.agencyMatched) StatusTone.Done else StatusTone.Neutral,
            )
        }
    },
)

/** The three groups the register's own filters use for an agency picker. */
internal fun List<Agency>.byName(): List<Agency> = sortedBy { it.name }

private val SEARCH_WIDTH = 240.dp
private val FILTER_WIDTH = 170.dp
private val STATUS_WIDTH = 120.dp
private val ACTION_WIDTH = 96.dp
private val CHECK_WIDTH = 56.dp
