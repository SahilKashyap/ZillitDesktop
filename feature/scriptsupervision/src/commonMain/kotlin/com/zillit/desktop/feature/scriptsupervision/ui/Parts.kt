package com.zillit.desktop.feature.scriptsupervision.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.domain.Rec

/**
 * The parts every screen in this module is built from — the web's
 * `components/PageHeader`, `LogParts` and `RowActions`.
 *
 * Thin wrappers over the design system rather than a second look: the web gave
 * this module the shared design system (`zds`) from the first commit, so
 * nothing here invents a style of its own.
 */

/** A screen's heading: its title, a line of description, and its actions. */
@Composable
fun SsPageHeader(
    title: String,
    sub: String? = null,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    ZillitPageHeader(title = title, description = sub, modifier = modifier, actions = actions)
}

/** "N records", the count beside a list's filter. */
@Composable
fun RecordCount(count: Int) {
    ZillitText(
        text = t(if (count == 1) "record_count_one" else "record_count_other", "n" to count),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
}

/** A muted line of text — a card's second line, a table cell's detail. */
@Composable
fun MutedText(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    ZillitText(
        text = text,
        modifier = modifier,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
        maxLines = maxLines,
    )
}

/** A tag: a flag, a status, a quality rating. */
@Composable
fun SsTag(label: String, tone: StatusTone = StatusTone.Neutral, modifier: Modifier = Modifier) {
    if (label.isBlank()) return
    ZillitStatusPill(label = label, tone = tone, modifier = modifier)
}

/** Edit and Delete at the end of a row, behind posting rights, as the web's `RowActions` is. */
@Composable
fun RowActions(onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    val ctx = LocalSs.current
    if (!ctx.canPost) return
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        onEdit?.let {
            ZillitIconButton(
                icon = ZillitIcons.Edit,
                contentDescription = t("edit"),
                onClick = it,
                size = ROW_ACTION,
            )
        }
        onDelete?.let {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = t("delete"),
                onClick = it,
                tint = ZillitTheme.colors.danger,
                size = ROW_ACTION,
            )
        }
    }
}

/**
 * What a screen shows while its fetch is in flight, and what it says when the
 * fetch failed — never a blank page either way.
 */
@Composable
fun <T> Await(resource: Resource<T>, content: @Composable ColumnScope.(T) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        when (val state = resource.state) {
            is Load.Loading -> MutedText(t("loading"))
            is Load.Failed -> LoadFailed(state.message) { resource.reload() }
            is Load.Ready -> content(state.value)
        }
    }
}

@Composable
private fun LoadFailed(message: String, onRetry: () -> Unit) {
    ZillitEmptyState(
        title = t("could_not_load"),
        message = message,
        action = { ZillitButton(text = t("try_again"), onClick = onRetry, variant = ButtonVariant.Secondary) },
    )
}

/**
 * The pager under a paged list — Previous, the page numbers around the current
 * one, Next, and "1–50 of 237".
 *
 * Scenes are the one list in this module the service pages (the continuity
 * folders answer the whole project), and [page] is ZERO-based because that is
 * what the service takes.
 */
@Composable
fun SsPager(page: Int, pageSize: Int, total: Int, onPage: (Int) -> Unit) {
    val pages = if (total <= 0) 0 else (total + pageSize - 1) / pageSize
    if (pages <= 1) return
    val first = page * pageSize + 1
    val last = minOf(total, (page + 1) * pageSize)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = t("previous"),
            onClick = { onPage(page - 1) },
            variant = ButtonVariant.Secondary,
            enabled = page > 0,
        )
        MutedText(t("page_range", "first" to first, "last" to last, "total" to total))
        ZillitButton(
            text = t("next"),
            onClick = { onPage(page + 1) },
            variant = ButtonVariant.Secondary,
            enabled = page + 1 < pages,
        )
    }
}

/** A scene as the lists name it: "Scene 14". Blank when the scene is not in the project's list. */
fun sceneText(scene: Rec?): String = scene?.str("scene_number")?.takeIf { it.isNotBlank() }
    // "Scene 14", composed as the web composes it, rather than a key of its own.
    ?.let { "${t("scene")} $it" }
    .orEmpty()

private val ROW_ACTION = 26.dp
