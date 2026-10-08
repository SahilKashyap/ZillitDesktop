package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.MergePerson
import com.zillit.desktop.feature.documentdistribution.domain.MergeSource
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.feature.documentdistribution.domain.buildMergePlan
import com.zillit.desktop.feature.documentdistribution.domain.describe
import com.zillit.desktop.feature.documentdistribution.domain.filteredFor
import com.zillit.desktop.feature.documentdistribution.domain.groupedForMerge
import com.zillit.desktop.feature.documentdistribution.domain.summary
import com.zillit.desktop.feature.documentdistribution.domain.withDefaults
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.MergeAction
import com.zillit.desktop.feature.documentdistribution.ui.MergeState
import com.zillit.desktop.feature.documentdistribution.ui.peopleForMerge

/**
 * "Merge PDFs to download or print" — the web's `MergePrintModal`.
 *
 * One question: who gets a copy. You are one of the answers, in a panel of
 * your own rather than a row in the list — your copy is the one you can choose
 * not to stamp, and the crew search must never be able to filter you out of
 * your own dialog.
 */
@Suppress("LongMethod") // One dialog; splitting it separates each panel from the state it reads.
@Composable
internal fun MergeDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val merge = state.merge
    val directory = remember(state.crew, state.contacts, state.viewer.userId, state.viewer.userEmail) {
        state.peopleForMerge()
    }
    val busy = merge?.busy != null
    val plan = merge?.let { open ->
        buildMergePlan(
            selection = open.documents,
            me = directory.me,
            includeSelf = open.includeSelf,
            watermarkSelf = open.watermarkSelf,
            chosen = directory.people.filter { it.email.lowercase() in open.picked },
        )
    }
    ZillitDialogShell(
        title = str(S.desktop_docdist_merge_title),
        subtitle = str(S.desktop_docdist_merge_subtitle),
        visible = merge != null,
        onDismiss = { if (!busy) onEvent(DocDistEvent.CloseMerge) },
        icon = ZillitIcons.File,
        width = DIALOG_WIDTH.dp,
        actions = {
            ZillitText(
                text = plan?.summary().orEmpty(),
                style = ZillitTheme.typography.label,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(DocDistEvent.CloseMerge) },
                variant = ButtonVariant.Tertiary,
                enabled = !busy,
            )
            ZillitButton(
                text = str(S.print),
                onClick = { onEvent(DocDistEvent.RunMerge(MergeAction.Print)) },
                variant = ButtonVariant.Secondary,
                enabled = plan?.isReady == true && !busy,
                loading = merge?.busy == MergeAction.Print,
                leadingIcon = ZillitIcons.Print,
            )
            ZillitButton(
                text = str(S.download),
                onClick = { onEvent(DocDistEvent.RunMerge(MergeAction.Download)) },
                enabled = plan?.isReady == true && !busy,
                loading = merge?.busy == MergeAction.Download,
                leadingIcon = ZillitIcons.Download,
            )
        },
    ) {
        if (merge == null || plan == null) return@ZillitDialogShell
        if (plan.skipped.isNotEmpty()) LeftOutNotice(plan.skipped.map { it.name })
        if (plan.documentCount == 0) {
            ZillitText(
                text = str(S.desktop_docdist_merge_no_pdfs),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        YourCopyPanel(merge, directory.me, state.viewer.userId, onEvent)
        CrewPanel(merge, directory.people, onEvent)
        WatermarkNote(state)
        merge.progress?.let { label ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitSpinner(size = 16.dp)
                ZillitText(text = label, style = ZillitTheme.typography.label)
            }
        }
        merge.error?.let { ZillitNotice(text = it, tone = StatusTone.Rejected, icon = ZillitIcons.Warning) }
    }
}

/** What the run will drop, named — a mixed selection is normal, not an error. */
@Composable
private fun LeftOutNotice(names: List<String>) {
    val c = ZillitTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(c.warningSoft)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = str(
                if (names.size == 1) S.desktop_docdist_merge_left_out_one else S.desktop_docdist_merge_left_out_many,
                names.size,
            ),
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = c.warning,
        )
        ZillitText(
            text = str(S.desktop_docdist_merge_only_pdfs),
            style = ZillitTheme.typography.bodySmall,
            color = c.warning,
        )
        names.take(LEFT_OUT_SHOWN).forEach { name ->
            ZillitText(text = "• $name", style = ZillitTheme.typography.bodySmall, color = c.warning, maxLines = 1)
        }
        if (names.size > LEFT_OUT_SHOWN) {
            ZillitText(
                text = "• " + str(S.desktop_n_more, names.size - LEFT_OUT_SHOWN),
                style = ZillitTheme.typography.bodySmall,
                color = c.warning,
            )
        }
    }
}

@Composable
private fun YourCopyPanel(merge: MergeState, me: MergePerson, viewerId: String, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    val busy = merge.busy != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        FieldLabel(str(S.desktop_docdist_merge_your_copy)) {
            // The state is spelled out, not left to be read off a switch
            // position: a client left the dialog alone and reported their own
            // copy as unwatermarked.
            ZillitSwitch(
                checked = merge.watermarkSelf,
                onCheckedChange = { onEvent(DocDistEvent.MergeWatermarkSelf(it)) },
                enabled = !busy && merge.includeSelf,
            )
            Column {
                ZillitText(
                    text = str(S.desktop_docdist_merge_watermark_your_copy),
                    style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    color = c.textPrimary,
                )
                ZillitText(
                    text = str(
                        if (merge.watermarkSelf) {
                            S.desktop_docdist_merge_stamped_with_name
                        } else {
                            S.desktop_docdist_merge_no_watermark
                        },
                    ),
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .border(0.5.dp, c.border, ZillitTheme.shapes.medium),
        ) {
            PersonRow(
                person = me,
                picked = merge.includeSelf,
                enabled = !busy,
                isYou = true,
                onToggle = { onEvent(DocDistEvent.MergeIncludeSelf(!merge.includeSelf)) },
                userId = viewerId,
            )
        }
    }
}

/** One row of the picker; [isYou] tags the signed-in person's own row. */
@Composable
private fun PersonRow(
    person: MergePerson,
    picked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    isYou: Boolean = false,
    userId: String = person.userId,
) {
    val c = ZillitTheme.colors
    val label = person.displayName.takeIf { it.isNotBlank() } ?: str(S.you)
    HoverRow(selected = picked, onClick = if (enabled) onToggle else null) {
        ZillitCheckbox(checked = picked, onCheckedChange = { onToggle() }, enabled = enabled)
        ZillitAvatar(name = label, size = AVATAR.dp, userId = userId.takeIf { it.isNotBlank() })
        Column(Modifier.weight(1f)) {
            // "You", not "Me": the dialog speaks to the user in the second
            // person everywhere else ("Your copy", "Watermark your copy").
            ZillitText(
                text = if (isYou && person.name.isNotBlank()) "$label · ${str(S.you)}" else label,
                style = ZillitTheme.typography.label,
                maxLines = 1,
            )
            if (person.job.isNotBlank()) {
                ZillitText(
                    text = person.job,
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textMuted,
                    maxLines = 1,
                )
            }
        }
        if (person.email.isNotBlank()) {
            ZillitText(
                text = person.email,
                style = ZillitTheme.typography.bodySmall,
                color = c.textMuted,
                maxLines = 1,
                modifier = Modifier.width(EMAIL_WIDTH.dp),
            )
        }
    }
}

/** The list rows once grouped: a department heading, or someone under it. */
private sealed interface MergeRow {
    data class Heading(val key: String, val label: String, val isContacts: Boolean) : MergeRow
    data class Member(val person: MergePerson) : MergeRow
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // One panel; head, list and empty states read the same filters.
@Composable
private fun CrewPanel(merge: MergeState, people: List<MergePerson>, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    val busy = merge.busy != null
    val shown = people.filteredFor(merge.source, merge.search)
    val otherLabel = str(S.other)
    val contactsLabel = str(S.contacts)
    val rows = remember(shown, otherLabel, contactsLabel) {
        shown.groupedForMerge(otherLabel, contactsLabel).flatMap { group ->
            listOf<MergeRow>(MergeRow.Heading(group.key, group.department, group.isContacts)) +
                group.members.map { MergeRow.Member(it) }
        }
    }
    val pickedCount = people.count { it.email.lowercase() in merge.picked }
    val allShown = shown.isNotEmpty() && shown.all { it.email.lowercase() in merge.picked }
    val filtering = merge.search.isNotBlank() || merge.source != MergeSource.All
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitCheckbox(
                checked = allShown,
                onCheckedChange = { onEvent(DocDistEvent.MergeToggleShown(it)) },
                label = str(if (filtering) S.desktop_payroll_select_shown else S.desktop_docdist_merge_select_all_crew),
                enabled = !busy && shown.isNotEmpty(),
            )
            // Counted against EVERYONE available, not the filtered view: the number
            // answers "how many am I sending to", which neither a search nor the
            // filter should appear to change.
            ZillitText(
                text = str(S.desktop_ce_selected_of, pickedCount, people.size),
                style = ZillitTheme.typography.bodySmall,
                color = c.textMuted,
                modifier = Modifier.weight(1f),
            )
            listOf(MergeSource.All to S.all, MergeSource.Crew to S.crew, MergeSource.Contacts to S.contacts)
                .forEach { (source, label) ->
                    ZillitChoiceChip(
                        label = str(label),
                        selected = merge.source == source,
                        onClick = { if (!busy) onEvent(DocDistEvent.MergeSetSource(source)) },
                    )
                }
        }
        ZillitSearchField(
            value = merge.search,
            onValueChange = { onEvent(DocDistEvent.MergeSearch(it)) },
            placeholder = str(S.desktop_search_crew),
            enabled = !busy,
        )
        Box(
            Modifier.fillMaxWidth().height(LIST_HEIGHT.dp)
                .clip(ZillitTheme.shapes.medium)
                .border(0.5.dp, c.border, ZillitTheme.shapes.medium),
        ) {
            if (rows.isEmpty()) {
                ZillitEmptyState(
                    title = str(
                        when {
                            people.isEmpty() -> S.desktop_docdist_merge_no_crew
                            merge.search.isNotBlank() -> S.desktop_docdist_merge_no_crew_match
                            merge.source == MergeSource.Contacts -> S.desktop_docdist_merge_no_contacts
                            else -> S.desktop_docdist_merge_no_crew
                        },
                    ),
                    icon = ZillitIcons.Users,
                )
            } else {
                ZillitLazyColumn(
                    Modifier.fillMaxWidth().height(LIST_HEIGHT.dp),
                    contentPadding = PaddingValues(ZillitTheme.spacing.xs),
                ) {
                    items(rows, key = { row ->
                        when (row) {
                            is MergeRow.Heading -> "h:${row.key}"
                            is MergeRow.Member -> "p:${row.person.email.lowercase()}"
                        }
                    }) { row ->
                        when (row) {
                            is MergeRow.Heading -> FieldLabel(
                                row.label,
                                Modifier.padding(
                                    start = ZillitTheme.spacing.sm,
                                    top = ZillitTheme.spacing.sm,
                                    bottom = ZillitTheme.spacing.xxs,
                                ),
                            )
                            is MergeRow.Member -> PersonRow(
                                person = row.person,
                                picked = row.person.email.lowercase() in merge.picked,
                                enabled = !busy,
                                onToggle = { onEvent(DocDistEvent.MergeToggle(row.person.email)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Watermark. Uses the project’s settings: Name · Large · #6b7280 · 40%".
 *
 * The appearance is named only once it is KNOWN: before the project's settings
 * load the built-in look is a placeholder, and stating it would promise a size
 * and colour the stamped copies will not have — the server fills them from the
 * saved settings when the request omits them.
 */
@Composable
private fun WatermarkNote(state: DocDistUiState) {
    val style = WatermarkStyle().withDefaults(state.watermarkDefaults)
    val appearance = if (state.watermarkDefaultsLoaded) " · " + state.watermarkDefaults.describe() else ""
    ZillitText(
        text = str(S.dd_watermark) + ". " + str(S.desktop_docdist_merge_uses_project_settings) + ": " +
            style.summary() + appearance,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
    )
}

private const val DIALOG_WIDTH = 780
private const val LIST_HEIGHT = 300
private const val AVATAR = 36
private const val EMAIL_WIDTH = 240
private const val LEFT_OUT_SHOWN = 6
