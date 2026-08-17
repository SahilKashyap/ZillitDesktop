package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText

/**
 * One Production Setup section, with its own save.
 *
 * ## The save only appears when there is something to save
 *
 * A page of nine sections each showing a permanently-enabled Save button reads
 * as nine pending actions. The button and the "Unsaved" pill both key off
 * dirtiness, so a page nobody has touched is quiet.
 *
 * ## Cancel sits beside it, not instead of it
 *
 * Discarding is the other half of an edit that has not been committed, and
 * without it the only way out of a half-made change is to reload the console
 * and lose everything else in progress too.
 */
@Composable
internal fun SetupSectionCard(
    title: String,
    description: String,
    dirty: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    onRevert: () -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ZillitSectionCard(modifier = modifier.fillMaxWidth(), title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = description,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (dirty) {
                    // Next to the title rather than only at the button, so the
                    // state is visible when the section is scrolled past.
                    ZillitStatusPill(label = "Unsaved", tone = StatusTone.Pending, dot = true)
                }
                action?.invoke()
            }

            content()

            if (editable && dirty) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        ZillitTheme.spacing.sm,
                        Alignment.End,
                    ),
                ) {
                    ZillitButton(
                        text = "Cancel",
                        onClick = onRevert,
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = !saving,
                    )
                    ZillitButton(
                        text = "Save changes",
                        onClick = onSave,
                        size = ButtonSize.Small,
                        loading = saving,
                    )
                }
            }
        }
    }
}

/** A read-only section — no save, because nothing here is edited in place. */
@Composable
internal fun SetupInfoCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SetupSectionCard(
        title = title,
        description = description,
        dirty = false,
        saving = false,
        onSave = {},
        onRevert = {},
        modifier = modifier,
        editable = false,
        action = action,
        content = content,
    )
}
