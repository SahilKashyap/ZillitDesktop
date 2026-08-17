package com.zillit.desktop.feature.esignature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.ui.pages.ComposeDialog
import com.zillit.desktop.feature.esignature.ui.pages.EnvelopeDetailPage
import com.zillit.desktop.feature.esignature.ui.pages.MarksDialog

/**
 * E-Signature — envelopes with typed fields, signed on the server's side.
 *
 * Two surfaces, as the web splits them: the manager's envelope buckets, and
 * everyone's "sign documents" list. A member without posting rights gets
 * only the second, which is the web's receiver-only branch.
 */
@Composable
fun EsignScreen(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        when {
            state.viewer.isBlocked -> {
                ZillitPageHeader(eyebrow = "Film Tools", title = "E-Signature")
                ZillitNotice(
                    text = "You don’t have access to E-Signature on this production.",
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Info,
                )
            }

            state.detail != null -> EnvelopeDetailPage(state, state.detail, onEvent)

            else -> ListsSurface(state, onEvent)
        }
    }

    ComposeDialog(state, onEvent)
    MarksDialog(state, onEvent)
}

@Composable
private fun ListsSurface(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    ZillitPageHeader(
        eyebrow = "Film Tools",
        title = "E-Signature",
        description = "Envelopes with placed fields — sign what reaches you, track " +
            "what you send.",
        actions = {
            if (state.viewer.canPost) {
                ZillitButton(
                    text = "Send for e-signature",
                    onClick = { onEvent(EsignEvent.StartCompose) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Send,
                )
            }
            ZillitButton(
                text = "Saved marks",
                onClick = { onEvent(EsignEvent.ToggleMarks) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
            )
            ZillitButton(
                text = "Refresh",
                onClick = { onEvent(EsignEvent.Refresh) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Reload,
                loading = state.manage.loading || state.signList.loading,
            )
        },
    )

    if (!state.viewer.receiverOnly) {
        ZillitTabStrip(
            tabs = EsignSurface.entries.map { ZillitTab(it.name, it.label) },
            activeId = state.surface.name,
            onSelect = { id ->
                EsignSurface.entries.firstOrNull { it.name == id }
                    ?.let { onEvent(EsignEvent.SwitchSurface(it)) }
            },
        )
    }

    when {
        state.surface == EsignSurface.Manage && !state.viewer.receiverOnly ->
            ManageList(state, onEvent)

        else -> SignList(state, onEvent)
    }
}

@Composable
private fun ManageList(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    ZillitTabStrip(
        tabs = ManageBucket.entries.map { ZillitTab(it.wire, it.label) },
        activeId = state.manage.bucket.wire,
        onSelect = { wire ->
            ManageBucket.entries.firstOrNull { it.wire == wire }
                ?.let { onEvent(EsignEvent.SwitchManageBucket(it)) }
        },
    )
    EnvelopeTable(
        rows = state.manage.rows,
        loading = state.manage.loading,
        emptyTitle = when (state.manage.bucket) {
            ManageBucket.Draft -> "No draft envelopes"
            ManageBucket.Sent -> "No sent envelopes"
            ManageBucket.Completed -> "No completed envelopes yet"
            ManageBucket.Rejected -> "No rejected envelopes"
        },
        showDelete = state.manage.bucket == ManageBucket.Draft && state.viewer.canPost,
        onEvent = onEvent,
    )
}

@Composable
private fun SignList(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    ZillitTabStrip(
        tabs = SignBucket.entries.map { ZillitTab(it.wire, it.label) },
        activeId = state.signList.bucket.wire,
        onSelect = { wire ->
            SignBucket.entries.firstOrNull { it.wire == wire }
                ?.let { onEvent(EsignEvent.SwitchSignBucket(it)) }
        },
    )
    EnvelopeTable(
        rows = state.signList.rows,
        loading = state.signList.loading,
        emptyTitle = when (state.signList.bucket) {
            SignBucket.Action -> "Nothing waiting for your signature"
            SignBucket.Completed -> "No completed envelopes yet"
            SignBucket.Rejected -> "No rejected envelopes"
        },
        showDelete = false,
        onEvent = onEvent,
    )
}

@Composable
private fun EnvelopeTable(
    rows: List<Envelope>,
    loading: Boolean,
    emptyTitle: String,
    showDelete: Boolean,
    onEvent: (EsignEvent) -> Unit,
) {
    ZillitSectionCard(padded = false, modifier = Modifier.fillMaxWidth()) {
        ZillitDataTable(
            rows = rows,
            key = { it.id },
            loading = loading,
            onRowClick = { onEvent(EsignEvent.OpenEnvelope(it)) },
            emptyTitle = emptyTitle,
            columns = buildList {
                add(
                    textColumn<Envelope>(header = "Document", width = ColumnWidth.Weight(2f)) {
                        it.title.ifBlank { it.document?.name ?: "(untitled)" }
                    },
                )
                add(textColumn(header = "Signers", muted = true) { it.signerSummary })
                add(
                    TableColumn(header = "Status") { envelope ->
                        ZillitStatusPill(
                            label = envelope.status.label.ifBlank { "—" },
                            tone = envelope.status.tone(),
                        )
                    },
                )
                if (showDelete) {
                    add(
                        TableColumn(header = "", width = ColumnWidth.Weight(0.5f)) { envelope ->
                            ZillitButton(
                                text = "Delete",
                                onClick = { onEvent(EsignEvent.DeleteDraft(envelope.id)) },
                                variant = ButtonVariant.Tertiary,
                                size = ButtonSize.Small,
                            )
                        },
                    )
                }
            },
        )
    }
}

internal fun EnvelopeStatus.tone(): StatusTone = when (this) {
    EnvelopeStatus.Completed, EnvelopeStatus.Signed -> StatusTone.Done
    EnvelopeStatus.Declined, EnvelopeStatus.Rejected,
    EnvelopeStatus.Voided, EnvelopeStatus.Expired,
    -> StatusTone.Rejected

    EnvelopeStatus.Draft -> StatusTone.Neutral
    else -> StatusTone.Pending
}
