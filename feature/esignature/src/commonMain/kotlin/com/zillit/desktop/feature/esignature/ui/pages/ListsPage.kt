@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod", "ComplexCondition")

package com.zillit.desktop.feature.esignature.ui.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TabStripSize
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyVerticalGrid
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.EsignFormat
import com.zillit.desktop.feature.esignature.ui.EsignEvent
import com.zillit.desktop.feature.esignature.ui.EsignSurface
import com.zillit.desktop.feature.esignature.ui.ManageBuckets
import com.zillit.desktop.feature.esignature.ui.EsignUiState
import com.zillit.desktop.feature.esignature.ui.ListLayout
import com.zillit.desktop.feature.esignature.ui.ManageInnerTab
import com.zillit.desktop.feature.esignature.ui.ManageOuterTab
import com.zillit.desktop.feature.esignature.ui.SentFilter
import com.zillit.desktop.feature.esignature.ui.SignBucket
import com.zillit.desktop.feature.esignature.ui.SigningMode
import com.zillit.desktop.feature.esignature.ui.components.ConfirmDialog
import com.zillit.desktop.feature.esignature.ui.components.CountBadge
import com.zillit.desktop.feature.esignature.ui.components.CountChip
import com.zillit.desktop.feature.esignature.ui.components.DeliveryPill
import com.zillit.desktop.feature.esignature.ui.components.DocTile
import com.zillit.desktop.feature.esignature.ui.components.EnvelopeStatusPill
import com.zillit.desktop.feature.esignature.ui.components.EsignCard
import com.zillit.desktop.feature.esignature.ui.components.LayoutToggle
import com.zillit.desktop.feature.esignature.ui.components.SignedProgress
import com.zillit.desktop.feature.esignature.ui.components.SignerAvatarGroup

/** The manager's envelopes — the web's `EnvelopeList`. */
@Composable
internal fun ManageListPage(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val manage = state.manage
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitTabStrip(
            size = TabStripSize.Primary,
            // The web's outer pills: each tab's bucket(s) of unread manage rows.
            tabs = ManageOuterTab.entries.map { tab ->
                val count = when (tab) {
                    ManageOuterTab.Active -> state.unread.manageBucket(ManageBuckets.SENT, ManageBuckets.DRAFT)
                    ManageOuterTab.Completed -> state.unread.manageBucket(ManageBuckets.COMPLETED)
                    ManageOuterTab.Rejected -> state.unread.manageBucket(ManageBuckets.REJECTED)
                }
                ZillitTab(tab.name, tab.label, count = count)
            },
            activeId = manage.outer.name,
            onSelect = { id ->
                ManageOuterTab.entries.firstOrNull { it.name == id }?.let { onEvent(EsignEvent.SwitchOuterTab(it)) }
            },
            trailing = {
                ZillitButton(
                    text = "Upload Doc for E-Signature",
                    onClick = { onEvent(EsignEvent.StartCompose) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Upload,
                )
                Spacer(Modifier.width(ZillitTheme.spacing.sm))
                LayoutToggle(isCard = state.layout == ListLayout.Card) {
                    onEvent(EsignEvent.SetLayout(if (it) ListLayout.Card else ListLayout.List))
                }
                Spacer(Modifier.width(ZillitTheme.spacing.sm))
                ZillitSearchField(
                    value = manage.search,
                    onValueChange = { onEvent(EsignEvent.SearchManage(it)) },
                    placeholder = "Search envelopes…",
                    modifier = Modifier.width(240.dp),
                )
            },
        )
        if (manage.outer == ManageOuterTab.Active) {
            ZillitTabStrip(
                tabs = ManageInnerTab.entries.map { tab ->
                    val count = if (tab == ManageInnerTab.Sent) manage.sent.size else manage.drafts.size
                    val bucket = if (tab == ManageInnerTab.Sent) ManageBuckets.SENT else ManageBuckets.DRAFT
                    ZillitTab(tab.name, "${tab.label}  ·  $count", count = state.unread.manageBucket(bucket))
                },
                activeId = manage.inner.name,
                onSelect = { id ->
                    ManageInnerTab.entries.firstOrNull { it.name == id }?.let { onEvent(EsignEvent.SwitchInnerTab(it)) }
                },
            )
        }
        if (manage.outer == ManageOuterTab.Active && manage.inner == ManageInnerTab.Sent) {
            if (manage.hiddenBulkSent > 0) {
                BulkHiddenBanner(manage.hiddenBulkSent) { onEvent(EsignEvent.SwitchSurface(EsignSurface.Bulk)) }
            }
            if (manage.sent.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    SentFilter.entries.forEach { filter ->
                        val count = when (filter) {
                            SentFilter.All -> manage.sent.size
                            SentFilter.Awaiting -> manage.awaiting.size
                            SentFilter.InProgress -> manage.inProgress.size
                        }
                        CountChip(
                            label = filter.label,
                            count = count,
                            active = manage.sentFilter == filter,
                            onClick = { onEvent(EsignEvent.SetSentFilter(filter)) },
                            accent = when (filter) {
                                SentFilter.All -> colors.accent
                                SentFilter.Awaiting -> colors.warning
                                SentFilter.InProgress -> colors.info
                            },
                            help = filter.help,
                        )
                    }
                }
            }
        }
        val rows = manage.visibleRows.searched(manage.search)
        val kind = when {
            manage.outer == ManageOuterTab.Completed -> RowKind.Completed
            manage.outer == ManageOuterTab.Rejected -> RowKind.Rejected
            manage.inner == ManageInnerTab.Draft -> RowKind.Draft
            else -> RowKind.Sent
        }
        val emptyTitle = when (kind) {
            RowKind.Draft -> "No draft envelopes"
            RowKind.Sent -> if (manage.hiddenBulkSent > 0 && manage.sent.isEmpty()) {
                "No directly-sent envelopes (${manage.hiddenBulkSent} via bulk send)"
            } else {
                "No sent envelopes"
            }
            RowKind.Completed -> "No completed envelopes yet"
            RowKind.Rejected -> "No rejected envelopes"
            RowKind.Received -> ""
        }
        AnimatedContent(
            targetState = Triple(kind, state.layout, manage.sentFilter),
            transitionSpec = { (fadeIn() + slideInVertically { it / 12 }) togetherWith fadeOut() },
            label = "manageRows",
        ) { (rowKind, layout, _) ->
            Box(Modifier.fillMaxSize()) {
                when {
                    rows.isEmpty() && !manage.loading -> EmptyRows(
                        title = emptyTitle,
                        message = when {
                            manage.search.isNotBlank() -> "Nothing matches “${manage.search}”."
                            rowKind == RowKind.Draft -> "Upload a PDF to start an envelope."
                            else -> null
                        },
                        action = if (rowKind == RowKind.Draft && manage.search.isBlank()) {
                            {
                                ZillitButton(
                                    "Upload a document",
                                    onClick = { onEvent(EsignEvent.StartCompose) },
                                    size = ButtonSize.Small,
                                    leadingIcon = ZillitIcons.Upload,
                                )
                            }
                        } else {
                            null
                        },
                    )
                    layout == ListLayout.Card -> EnvelopeCardGrid(rows, rowKind, state, onEvent)
                    else -> EnvelopeTable(rows, rowKind, manage.loading, emptyTitle, state, onEvent)
                }
            }
        }
    }
    ConfirmDialog(
        visible = manage.confirmDeleteId != null,
        title = "Delete this envelope?",
        body = "This draft will be permanently removed.",
        confirmLabel = "Delete",
        onConfirm = { onEvent(EsignEvent.ConfirmDeleteDraft) },
        onDismiss = { onEvent(EsignEvent.AskDeleteDraft(null)) },
    )
}

/** The receiver's envelopes — the web's `ReceivedEnvelopes`. */
@Composable
internal fun SignListPage(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val list = state.signList
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitTabStrip(
            size = TabStripSize.Primary,
            // Action Required wears the signer's unread rows (the web's `signDocuments.total`);
            // the settled tabs read themselves on entry and wear nothing.
            tabs = SignBucket.entries.map { bucket ->
                ZillitTab(
                    bucket.name,
                    bucket.label,
                    count = if (bucket == SignBucket.Action) state.unread.sign else 0,
                )
            },
            activeId = list.tab.name,
            onSelect = { id ->
                SignBucket.entries.firstOrNull { it.name == id }?.let { onEvent(EsignEvent.SwitchSignBucket(it)) }
            },
            trailing = {
                LayoutToggle(isCard = state.layout == ListLayout.Card) {
                    onEvent(EsignEvent.SetLayout(if (it) ListLayout.Card else ListLayout.List))
                }
                Spacer(Modifier.width(ZillitTheme.spacing.sm))
                ZillitSearchField(
                    value = list.search,
                    onValueChange = { onEvent(EsignEvent.SearchSign(it)) },
                    placeholder = "Search documents…",
                    modifier = Modifier.width(240.dp),
                )
            },
        )
        val rows = list.visibleRows.searched(list.search)
        val kind = when (list.tab) {
            SignBucket.Action -> RowKind.Received
            SignBucket.Completed -> RowKind.Completed
            SignBucket.Rejected -> RowKind.Rejected
        }
        val emptyTitle = when (list.tab) {
            SignBucket.Action -> "Nothing waiting for your signature"
            SignBucket.Completed -> "No completed documents yet"
            SignBucket.Rejected -> "No rejected documents"
        }
        AnimatedContent(
            targetState = kind to state.layout,
            transitionSpec = { (fadeIn() + slideInVertically { it / 12 }) togetherWith fadeOut() },
            label = "signRows",
        ) { (rowKind, layout) ->
            Box(Modifier.fillMaxSize()) {
                when {
                    rows.isEmpty() && !list.loading -> EmptyRows(
                        emptyTitle,
                        if (rowKind == RowKind.Received) {
                            "Documents sent to you for signing will appear here."
                        } else {
                            null
                        },
                    )
                    layout == ListLayout.Card -> EnvelopeCardGrid(rows, rowKind, state, onEvent)
                    else -> EnvelopeTable(rows, rowKind, list.loading, emptyTitle, state, onEvent)
                }
            }
        }
    }
}

internal enum class RowKind { Draft, Sent, Completed, Rejected, Received }

private fun List<Envelope>.searched(query: String): List<Envelope> {
    if (query.isBlank()) return this
    val q = query.trim()
    return filter { e ->
        e.title.contains(q, true) || e.recipients.any { it.name.contains(q, true) || it.email.contains(q, true) }
    }
}

@Composable
private fun EmptyRows(title: String, message: String? = null, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
        ZillitEmptyState(title = title, message = message, icon = ZillitIcons.File, action = action)
    }
}

@Composable
private fun BulkHiddenBanner(count: Int, onJump: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.accentSoft)
            .border(1.dp, colors.accent.copy(alpha = 0.4f), ZillitTheme.shapes.medium)
            .clickable(onClick = onJump)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(icon = ZillitIcons.Send, tint = colors.accentText, size = 14.dp)
        ZillitText(
            "$count envelope${if (count == 1) "" else "s"} hidden from bulk sends",
            style = ZillitTheme.typography.label,
            color = colors.accentText,
            modifier = Modifier.weight(1f),
        )
        ZillitText("Manage in Bulk Sends →", style = ZillitTheme.typography.labelSmall, color = colors.accentText)
    }
}

// ---------------------------------------------------------------- table

@Composable
private fun EnvelopeTable(
    rows: List<Envelope>,
    kind: RowKind,
    loading: Boolean,
    emptyTitle: String,
    state: EsignUiState,
    onEvent: (EsignEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val terminal = kind == RowKind.Completed || kind == RowKind.Rejected
    Box(
        Modifier.fillMaxSize().clip(ZillitTheme.shapes.large).background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        ZillitDataTable(
            rows = rows,
            key = { it.id },
            loading = loading,
            emptyTitle = emptyTitle,
            onRowClick = { onEvent(EsignEvent.OpenEnvelope(it)) },
            columns = buildList {
                add(
                    TableColumn(header = "Document name", width = ColumnWidth.Weight(2.2f)) { envelope ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            DocTile(size = 30.dp)
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    ZillitText(
                                        envelope.title.ifBlank { envelope.document?.name ?: "(untitled)" },
                                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    // The row's unread on this tab — the web's Details-button badge.
                                    CountBadge(state.rowUnread(envelope, kind))
                                }
                                envelope.document?.name?.takeIf { it.isNotBlank() && it != envelope.title }?.let {
                                    ZillitText(
                                        it,
                                        style = ZillitTheme.typography.labelSmall,
                                        color = colors.textMuted,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    },
                )
                if (kind == RowKind.Sent || kind == RowKind.Received) {
                    add(TableColumn(header = "Signed by", width = ColumnWidth.Fixed(150.dp)) { SignedProgress(it) })
                }
                add(
                    TableColumn(header = "Signers", width = ColumnWidth.Fixed(150.dp)) {
                        SignerAvatarGroup(it.recipients, maxCount = 4)
                    },
                )
                add(
                    TableColumn(
                        header = "Status",
                        width = ColumnWidth.Fixed(if (terminal) 200.dp else 130.dp),
                    ) { envelope ->
                        when (kind) {
                            RowKind.Sent -> DeliveryPill(envelope)
                            RowKind.Received -> ZillitStatusPill(
                                label = if (envelope.awaitsMyCounterSign(state)) {
                                    "Counter-sign"
                                } else {
                                    "Pending signature"
                                },
                                tone = StatusTone.Pending,
                                dot = true,
                            )
                            RowKind.Completed -> EnvelopeStatusPill(
                                envelope.status,
                                "Completed ${EsignFormat.date(envelope.completedOn ?: envelope.updated)}",
                            )
                            RowKind.Rejected -> EnvelopeStatusPill(
                                EnvelopeStatus.Declined,
                                "Rejected ${EsignFormat.date(envelope.updated ?: envelope.completedOn)}",
                            )
                            RowKind.Draft -> EnvelopeStatusPill(envelope.status)
                        }
                    },
                )
                if (!terminal) {
                    add(
                        TableColumn(
                            header = if (kind == RowKind.Draft) "Last change" else "Sent on",
                            width = ColumnWidth.Fixed(110.dp),
                        ) { envelope ->
                            ZillitText(
                                EsignFormat.date(
                                    if (kind == RowKind.Draft) {
                                        envelope.lastActivity
                                    } else {
                                        envelope.sentOn ?: envelope.created
                                    },
                                ),
                                style = ZillitTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        },
                    )
                }
                add(
                    TableColumn(
                        header = "",
                        width = ColumnWidth.Fixed(if (kind == RowKind.Received) 250.dp else 220.dp),
                    ) { envelope ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RowActions(envelope, kind, state, onEvent)
                        }
                    },
                )
            },
        )
    }
}

@Composable
private fun RowActions(envelope: Envelope, kind: RowKind, state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    when (kind) {
        RowKind.Draft -> {
            ZillitButton(
                "Edit",
                onClick = { onEvent(EsignEvent.OpenEnvelope(envelope)) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Edit,
            )
            ZillitButton(
                "Delete",
                onClick = { onEvent(EsignEvent.AskDeleteDraft(envelope.id)) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Trash,
            )
        }
        RowKind.Received -> {
            val mine = envelope.recipientFor(state.currentUserId, state.currentUserEmail)
            if (mine != null) {
                ZillitButton(
                    if (envelope.awaitsMyCounterSign(state)) "Counter-sign" else "Sign",
                    onClick = { onEvent(EsignEvent.OpenSigning(envelope, SigningMode.Sign)) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Edit,
                )
            }
            ZillitButton(
                "View",
                onClick = { onEvent(EsignEvent.OpenSigning(envelope, SigningMode.ViewSigned)) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Eye,
            )
        }
        else -> {
            ZillitButton(
                "Details",
                onClick = { onEvent(EsignEvent.OpenEnvelope(envelope)) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Info,
            )
            ZillitButton(
                "View",
                onClick = { onEvent(EsignEvent.OpenSigning(envelope, SigningMode.ViewSigned)) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Eye,
            )
        }
    }
}

/** Every other signer has signed and only I remain — the web's `isAwaitingCounterSign`. */
internal fun Envelope.awaitsMyCounterSign(state: EsignUiState): Boolean {
    val me = recipientFor(state.currentUserId, state.currentUserEmail) ?: return false
    if (me.signed) return false
    val others = signers.filter { it !== me }
    return others.isNotEmpty() && others.all { it.signed }
}

// ---------------------------------------------------------------- cards

@Composable
private fun EnvelopeCardGrid(rows: List<Envelope>, kind: RowKind, state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitLazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 340.dp),
        modifier = Modifier.fillMaxSize().clip(ZillitTheme.shapes.large).background(colors.surfaceSunken),
        contentPadding = PaddingValues(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        items(rows, key = { it.id }) { envelope -> EnvelopeCard(envelope, kind, state, onEvent) }
    }
}

@Composable
private fun EnvelopeCard(envelope: Envelope, kind: RowKind, state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val counterSign = kind == RowKind.Received && envelope.awaitsMyCounterSign(state)
    EsignCard(
        onClick = { onEvent(EsignEvent.OpenEnvelope(envelope)) },
        accent = if (counterSign || kind == RowKind.Received) colors.accent else null,
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DocTile()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ZillitText(
                        envelope.title.ifBlank { envelope.document?.name ?: "(untitled)" },
                        style = ZillitTheme.typography.titleSmall,
                        maxLines = 2,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    CountBadge(state.rowUnread(envelope, kind))
                }
                ZillitText(
                    buildString {
                        append(EsignFormat.date(envelope.sentOn ?: envelope.lastActivity))
                        envelope.document?.sizeBytes?.takeIf { it > 0 }?.let { append(" · ${EsignFormat.size(it)}") }
                        envelope.document?.pageCount?.takeIf { it > 0 }?.let {
                            append(" · $it page${if (it == 1) "" else "s"}")
                        }
                    },
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            when (kind) {
                RowKind.Sent -> DeliveryPill(envelope)
                RowKind.Received -> ZillitStatusPill(
                    label = if (counterSign) "Counter-sign" else "Action required",
                    tone = StatusTone.Pending,
                    dot = true,
                )
                RowKind.Completed -> EnvelopeStatusPill(EnvelopeStatus.Completed)
                RowKind.Rejected -> EnvelopeStatusPill(EnvelopeStatus.Declined)
                RowKind.Draft -> EnvelopeStatusPill(EnvelopeStatus.Draft)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SignerAvatarGroup(envelope.recipients, maxCount = 5)
            if (kind == RowKind.Sent || kind == RowKind.Received) {
                SignedProgress(envelope)
            } else {
                ZillitText(
                    envelope.signerSummary,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
        if (counterSign) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(ZillitTheme.shapes.small).background(colors.accentSoft).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitIcon(ZillitIcons.Info, tint = colors.accentText, size = 13.dp)
                ZillitText(
                    "Everyone else has signed — your counter-signature completes it.",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.accentText,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            RowActions(envelope, kind, state, onEvent)
        }
    }
}

/**
 * One row's unread for the tab it is on: a manage row counts only its own
 * bucket's rows (`byEnvelopeByBucket`), a signer's row every row about it.
 */
private fun EsignUiState.rowUnread(envelope: Envelope, kind: RowKind): Int = when (kind) {
    RowKind.Received -> unread.signEnvelope(envelope.id)
    RowKind.Sent -> unread.manageEnvelope(envelope.id, ManageBuckets.SENT)
    RowKind.Draft -> unread.manageEnvelope(envelope.id, ManageBuckets.DRAFT)
    RowKind.Completed -> if (surface == EsignSurface.Sign) {
        unread.signEnvelope(envelope.id)
    } else {
        unread.manageEnvelope(envelope.id, ManageBuckets.COMPLETED)
    }
    RowKind.Rejected -> if (surface == EsignSurface.Sign) {
        unread.signEnvelope(envelope.id)
    } else {
        unread.manageEnvelope(envelope.id, ManageBuckets.REJECTED)
    }
}
