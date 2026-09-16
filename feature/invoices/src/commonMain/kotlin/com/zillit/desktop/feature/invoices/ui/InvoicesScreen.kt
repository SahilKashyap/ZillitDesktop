// The page shell; the department and accountant bodies live under ui/pages.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.invoices.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.domain.HoldReason
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.ui.pages.AccountantPageContent
import com.zillit.desktop.feature.invoices.ui.pages.AssignSheet
import com.zillit.desktop.feature.invoices.ui.pages.DeleteDialog
import com.zillit.desktop.feature.invoices.ui.pages.DepartmentPage
import com.zillit.desktop.feature.invoices.ui.pages.EnterInvoiceDialog
import com.zillit.desktop.feature.invoices.ui.pages.InvoiceDetailDialog
import com.zillit.desktop.feature.invoices.ui.pages.PoReviewOverlay
import com.zillit.desktop.feature.invoices.ui.pages.ProcessSheet
import com.zillit.desktop.feature.invoices.ui.pages.RejectRunSheet
import com.zillit.desktop.feature.invoices.ui.pages.RunAuthPickerSheet
import com.zillit.desktop.feature.invoices.ui.pages.SalesInvoiceSheet
import com.zillit.desktop.feature.invoices.ui.pages.SetupConfirmSheets
import com.zillit.desktop.feature.invoices.ui.pages.TeamMemberSheet
import com.zillit.desktop.feature.invoices.ui.pages.UploadDialog

/**
 * Invoices: the department board for crew, and for the accounts department the
 * web's sidebar over the register, the inbox, the approval queue and the
 * dashboard, plus the shared detail dialog.
 */
@Composable
fun InvoicesScreen(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    nowMs: Long,
) {
    val accountant = state.isAccountant
    val screenFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { screenFocus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas)
            .focusRequester(screenFocus)
            .focusable()
            // `onKeyEvent`, not the preview: a focused text field consumes its
            // own keys first, so typing "n" in a search box types an n.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                handleShortcut(
                    shortcut = InvoiceShortcut.of(event.utf16CodePoint.toChar().toString(), event.isShiftPressed),
                    state = state,
                    onEvent = onEvent,
                    focusSearch = { runCatching { searchFocus.requestFocus() } },
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            // The web gives every screen its own header — an eyebrow naming
            // the stage, the screen's title and its own sentence — rather
            // than one module heading over all sixteen.
            ZillitPageHeader(
                title = if (accountant) state.page.heading else "Invoices",
                eyebrow = state.page.eyebrow.takeIf { accountant },
                description = if (accountant) {
                    state.page.blurb
                } else {
                    "Supplier invoices: the ones waiting on you, your department's, and your own uploads."
                },
                actions = {
                    ZillitButton(
                        text = "Refresh",
                        onClick = { onEvent(InvoicesEvent.Refresh) },
                        variant = ButtonVariant.Tertiary,
                        loading = state.loading,
                    )
                    if (accountant && state.page == AccountantPage.Inbox) {
                        ZillitButton(
                            text = "Enter Invoice",
                            onClick = { onEvent(InvoicesEvent.OpenEnter) },
                            leadingIcon = ZillitIcons.Add,
                        )
                    }
                    if (!accountant && state.viewer.mayPost) {
                        ZillitButton(
                            text = "Upload Invoice",
                            onClick = { onEvent(InvoicesEvent.UploadInvoice) },
                            leadingIcon = ZillitIcons.Upload,
                            enabled = state.upload == null,
                        )
                    }
                },
            )
            if (state.viewer.isBlocked) ZillitNotice(text = "You do not have access to the Invoices tool.")
            state.error?.let { message ->
                ZillitNotice(
                    text = message,
                    tone = StatusTone.Rejected,
                    action = {
                        ZillitButton(
                            text = "Dismiss",
                            onClick = { onEvent(InvoicesEvent.DismissError) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    },
                )
            }
            if (accountant) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    InvoiceSidebar(state, nowMs, onEvent)
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    ) {
                        AccountantPageContent(state, onEvent, nowMs, searchFocus)
                    }
                }
            } else {
                DepartmentPage(state, onEvent)
            }
        }
        state.detail?.let { InvoiceDetailDialog(state, it, onEvent) }
        state.upload?.let { UploadDialog(state, it, onEvent) }
        state.enter?.let { EnterInvoiceDialog(state, it, onEvent) }
        state.confirmDelete?.let { DeleteDialog(state, it, onEvent) }
        state.runDraft?.let { ProcessSheet(state, it, onEvent) }
        state.rejectRun?.let { RejectRunSheet(it, onEvent) }
        state.assignFor?.let { AssignSheet(state, it, onEvent) }
        state.salesDraft?.let { SalesInvoiceSheet(state, it, onEvent) }
        state.review?.let { PoReviewOverlay(state, it, onEvent) }
        // After the review, not before: holding is reached from inside it, and
        // a dialog declared earlier would open behind the overlay that raised it.
        state.holdFor?.let { HoldDialog(it, onEvent) }
        TeamMemberSheet(state, onEvent)
        RunAuthPickerSheet(state, onEvent)
        SetupConfirmSheets(state, onEvent)
        if (state.shortcutsOpen) ShortcutsDialog(onEvent)
    }
}

/**
 * The accountant's sidebar — the web's `Sidebar.jsx`, part for part.
 *
 * Its own titled block at the top, an icon on every row, a hairline between
 * groups and the shortcut strip at the foot. Every row the web has is here;
 * the ones this build has not ported are dimmed rather than hidden, because a
 * shorter list would misrepresent the module.
 */
@Composable
private fun InvoiceSidebar(state: InvoicesUiState, nowMs: Long, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val pages = AccountantPage.visibleTo(state.viewer)
    Column(
        modifier = Modifier
            .width(SIDEBAR_WIDTH)
            .fillMaxHeight()
            .background(colors.surfaceSunken),
    ) {
        SidebarHeader(nowMs)
        HairLine()
        ZillitScrollColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            InvoiceNavGroup.entries.forEachIndexed { index, group ->
                val items = pages.filter { it.group == group }
                if (items.isEmpty()) return@forEachIndexed
                if (index > 0) {
                    HairLine(Modifier.padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs))
                }
                group.label?.let { heading ->
                    ZillitText(
                        text = heading.uppercase(),
                        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.textMuted,
                        modifier = Modifier.padding(
                            start = ZillitTheme.spacing.sm,
                            top = ZillitTheme.spacing.xs,
                            bottom = ZillitTheme.spacing.xxs,
                        ),
                        maxLines = 1,
                    )
                }
                items.forEach { page ->
                    SidebarRow(
                        page = page,
                        active = page == state.page,
                        badge = state.sidebarBadge(page),
                        unread = page.badgeKey?.let(state.unread::get) ?: 0,
                        onEvent = onEvent,
                    )
                }
            }
        }
        HairLine()
        SidebarFooter()
    }
}

/** The module's name, and the period the figures on screen belong to. */
@Composable
private fun SidebarHeader(nowMs: Long) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = "Invoices / Accounts Payable",
            style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 2,
        )
        ZillitText(
            // The web prints a fixed period here; this one is the real date,
            // in the same shape.
            text = InvoiceFormat.periodLabel(nowMs),
            style = ZillitTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = ZillitTheme.colors.accentText,
            maxLines = 1,
        )
    }
}

@Composable
private fun SidebarRow(
    page: AccountantPage,
    active: Boolean,
    badge: Int?,
    onEvent: (InvoicesEvent) -> Unit,
    unread: Int = 0,
) {
    val colors = ZillitTheme.colors
    val ink = if (active) colors.accentText else colors.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(if (active) colors.accentSoft else Color.Transparent)
            .clickable { onEvent(InvoicesEvent.SelectPage(page)) }
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = page.icon, tint = ink, size = ZillitDimens.iconSmall)
        ZillitText(
            text = page.label,
            style = ZillitTheme.typography.bodyMedium.copy(
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            ),
            color = ink,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        // Unread notifications filed under the page — the web's red sidebar chip.
        ZillitBadge(count = unread)
        if (badge != null && badge > 0) {
            ZillitText(
                text = badge.toString(),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                color = if (active) colors.accentText else colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/** The web's keyboard strip. Only the keys this build actually answers are offered. */
@Composable
private fun SidebarFooter() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        InvoiceShortcut.entries.forEachIndexed { index, shortcut ->
            if (index > 0) {
                ZillitText(
                    text = "·",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            KeyCap(shortcut.key)
            ZillitText(
                text = shortcut.label,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

private val SIDEBAR_WIDTH = 250.dp

/**
 * Holding an invoice for query — the web's `HoldForQueryModal`.
 *
 * A reason is required, and "Other" is refused without the notes that explain
 * it. A failed hold keeps what was typed, so the dialog stays until it lands.
 */
@Composable
private fun HoldDialog(request: HoldRequest, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = if (request.invoices.size == 1) "Hold for query" else "Hold ${request.invoices.size} invoices",
        subtitle = "It stays out of the approval chain until the query is answered.",
        visible = true,
        onDismiss = { if (!request.busy) onEvent(InvoicesEvent.CancelHold) },
        icon = ZillitIcons.Warning,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(InvoicesEvent.CancelHold) },
                variant = ButtonVariant.Tertiary,
                enabled = !request.busy,
            )
            ZillitButton(
                text = "Hold",
                onClick = { onEvent(InvoicesEvent.ConfirmHold) },
                enabled = request.isReady && !request.busy,
                loading = request.busy,
            )
        },
    ) {
        ZillitSelect(
            value = request.reason,
            options = listOf<HoldReason?>(null) + HoldReason.entries,
            onSelect = { reason -> reason?.let { onEvent(InvoicesEvent.HoldReasonChanged(it)) } },
            label = { it?.label ?: "Select reason…" },
            enabled = !request.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = request.notes,
            onValueChange = { onEvent(InvoicesEvent.HoldNotesChanged(it)) },
            label = if (request.reason?.needsNotes() == true) "Query notes (required)" else "Query notes",
            singleLine = false,
            enabled = !request.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A hairline, as the web's `h-px bg-border` divides the sidebar's groups. */
@Composable
private fun HairLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
}

/**
 * Acts on a keystroke, or lets it pass.
 *
 * Nothing fires while a dialog is up — it owns the keyboard — except that the
 * shortcuts sheet is what `?` opens in the first place.
 */
private fun handleShortcut(
    shortcut: InvoiceShortcut?,
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    focusSearch: () -> Unit,
): Boolean {
    if (shortcut == null || state.dialogOpen) return false
    when (shortcut) {
        InvoiceShortcut.Search -> focusSearch()
        InvoiceShortcut.Help -> onEvent(InvoicesEvent.OpenShortcuts)
        InvoiceShortcut.New -> when {
            // The accountant enters one; everyone else uploads theirs.
            state.isAccountant -> onEvent(InvoicesEvent.OpenEnter)
            state.viewer.mayPost -> onEvent(InvoicesEvent.UploadInvoice)
            else -> return false
        }
    }
    return true
}

/** What the footer advertises, spelled out — the sheet `?` opens. */
@Composable
private fun ShortcutsDialog(onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = "Keyboard shortcuts",
        visible = true,
        onDismiss = { onEvent(InvoicesEvent.CloseShortcuts) },
        icon = ZillitIcons.Help,
        actions = {
            ZillitButton(
                text = "Close",
                onClick = { onEvent(InvoicesEvent.CloseShortcuts) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        InvoiceShortcut.entries.forEach { shortcut ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                KeyCap(shortcut.key)
                ZillitText(text = shortcut.label, style = ZillitTheme.typography.bodyMedium)
            }
        }
    }
}

/** One key, drawn as a key — the web's `<kbd>`. */
@Composable
private fun KeyCap(text: String) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.small)
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = 1.dp),
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = colors.textSecondary,
        )
    }
}
