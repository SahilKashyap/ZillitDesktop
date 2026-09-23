package com.zillit.desktop.feature.settings.admin.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.ui.AdminEvent
import com.zillit.desktop.feature.settings.admin.ui.AdminUiState

/**
 * The frame every administration page sits in.
 *
 * Written once because the sixteen pages differ only in their middle. Each one
 * has a title, a way back, usually a search box, one action in the corner, and
 * the same two strips — what just happened, and what went wrong. Repeating that
 * per page is how sixteen pages end up with fourteen slightly different error
 * treatments.
 *
 * @param search null on the pages that are a form rather than a list. A search
 *   box over a single form is furniture.
 */
@Composable
@Suppress("LongParameterList") // Page chrome; each parameter is one visible part.
fun AdminPage(
    title: String,
    description: String,
    state: AdminUiState,
    onEvent: (AdminEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    search: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
    /**
     * True when this page's own body scrolls — a table, a long list.
     *
     * The distinction is not cosmetic: a lazy list measured inside a scrolling
     * column gets an infinite height constraint, which Compose refuses outright
     * rather than degrading. Pages that stack sections leave this false.
     */
    bodyScrolls: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = PAGE_WIDTH).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Header(title, description, state, onBack, action)

                state.outcome?.let { outcome ->
                    Strip(
                        text = outcome,
                        tint = ZillitTheme.colors.successSoft,
                        icon = ZillitIcons.Check,
                        iconTint = ZillitTheme.colors.success,
                        onDismiss = { onEvent(AdminEvent.DismissOutcome) },
                    )
                }
                state.error?.let { error ->
                    Strip(
                        text = error,
                        tint = ZillitTheme.colors.dangerSoft,
                        icon = ZillitIcons.Warning,
                        iconTint = ZillitTheme.colors.danger,
                        onDismiss = { onEvent(AdminEvent.DismissError) },
                    )
                }

                if (search != null) {
                    ZillitSearchField(
                        value = state.query,
                        onValueChange = { onEvent(AdminEvent.SearchChanged(it)) },
                        placeholder = search,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                val body = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .let { if (bodyScrolls) it else it.zillitVerticalScroll() }

                Column(
                    modifier = body,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun Header(
    title: String,
    description: String,
    state: AdminUiState,
    onBack: () -> Unit,
    action: (@Composable RowScope.() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIconButton(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = str(S.desktop_back_to_admin_settings),
            onClick = onBack,
        )
        Column(Modifier.weight(1f)) {
            ZillitText(text = title, style = ZillitTheme.typography.titleLarge)
            ZillitText(
                text = description,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }

        // One spinner for the page, beside the action rather than over it: a
        // save that greys out the whole page reads as a page that has stopped
        // responding.
        if (state.isSaving || state.isLoading) ZillitSpinner(size = SPINNER)

        action?.invoke(this)
    }
}

/**
 * A message above the page — what happened, or what went wrong.
 *
 * Dismissible so it can be got out of the way, and it does not time out: an
 * administrator who looked away should still be able to read what the last
 * click did.
 */
@Composable
fun Strip(
    text: String,
    tint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(tint)
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = icon, contentDescription = null, tint = iconTint, size = STRIP_GLYPH)
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (onDismiss != null) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.sync_action_dismiss),
                onClick = onDismiss,
                size = STRIP_BUTTON,
            )
        }
    }
}

/**
 * A card holding a run of rows separated by hairlines.
 *
 * One card per list rather than a card per row: twenty outlined rows is a
 * ladder, and the border repeats itself without saying anything.
 */
@Composable
fun RowCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surface)
            .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.medium),
        content = content,
    )
}

/**
 * The rule between two rows in a [RowCard].
 *
 * A `Box` rather than the design system's divider: that one fills its width,
 * and a full-width child inside a horizontal parent collapses the whole row to
 * nothing. Here the parent is a column, but the same component is reached for
 * inside rows by reflex, and this one cannot be misused that way.
 */
@Composable
fun RowRule(inset: Dp = 0.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(HAIRLINE)
            .background(ZillitTheme.colors.divider),
    )
}

/**
 * A row that can be selected — a department whose job titles are open, a person
 * whose rights are shown.
 *
 * Selection is a tinted background rather than a checkmark: these lists are
 * navigation, and a tick suggests something is being collected.
 */
@Composable
fun SelectableRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (selected) ZillitTheme.colors.surfaceSelected else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        content = content,
    )
}

/**
 * What a list says when it is empty, and why.
 *
 * Takes the reason rather than a generic line: "no departments" and "nothing
 * matches your search" are different states, and telling them apart is the
 * difference between adding one and clearing the filter.
 */
@Composable
fun EmptyRow(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/**
 * A small destructive button, for the delete at the end of a row.
 *
 * [onClick] is last so the call sites read `RemoveButton("Delete") { … }`. It
 * matters: with `enabled` last, that trailing lambda binds to the wrong
 * parameter and the compiler's complaint points at the argument rather than
 * the signature.
 */
@Composable
fun RemoveButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    ZillitButton(
        text = label,
        onClick = onClick,
        variant = ButtonVariant.Danger,
        size = ButtonSize.Small,
        enabled = enabled,
    )
}

private val PAGE_WIDTH = 900.dp
private val HAIRLINE = 1.dp
private val STRIP_GLYPH = 16.dp
private val STRIP_BUTTON = 22.dp
private val SPINNER = 16.dp
