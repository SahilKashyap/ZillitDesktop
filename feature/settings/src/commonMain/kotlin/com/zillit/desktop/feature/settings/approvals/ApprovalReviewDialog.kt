package com.zillit.desktop.feature.settings.approvals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.units.ProductionUnit

/**
 * One request, opened to be looked at properly.
 *
 * ## Why a form at all
 *
 * The list can already approve and decline, and for most requests that is the
 * whole job. This exists for the rest: someone who picked the wrong department
 * when they joined, or picked one before the production had the right one.
 * Both phone clients put the same three pickers behind a tap on the row, and
 * without them an admin's only options are to admit someone into the wrong
 * department or to refuse them outright.
 *
 * ## A dialog, not a page
 *
 * Three pickers and a checkbox. A route per request would need an id in the
 * path — ids that come from the server and do not survive the queue being
 * re-read — and it would take the queue off screen, which is the thing the
 * admin is working through.
 */
@Composable
fun ApprovalReviewDialog(
    state: ApprovalsUiState,
    onEvent: (ApprovalsEvent) -> Unit,
    known: (String) -> KnownCrewMember?,
) {
    val review = state.review

    ZillitDialogShell(
        title = review?.queue?.reviewTitle ?: "",
        subtitle = review?.request?.displayName,
        icon = ZillitIcons.User,
        visible = review != null,
        onDismiss = { onEvent(ApprovalsEvent.Review.Close) },
        width = DIALOG_WIDTH,
    ) {
        // Composed only when there is something to show: the shell keeps its
        // content alive through the exit animation, and reading a null review
        // there would take the window down on the way out.
        if (review == null) return@ZillitDialogShell

        Person(review.request, known(review.request.userId), review.queue, state[review.queue])
        Placement(review, state.presets, onEvent)

        review.error?.let { error ->
            ZillitText(
                text = error,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
            )
        }

        Actions(saving = state.isReviewSaving, onEvent = onEvent)
    }
}

/** Who this is, and — on the change queue — what they are asking to change. */
@Composable
private fun Person(
    request: PendingApproval,
    known: KnownCrewMember?,
    queue: ApprovalQueue,
    queueState: ApprovalQueueState,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitAvatar(name = request.displayName, size = AVATAR)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            val meta = listOfNotNull(
                request.email,
                waitedFor(request.requestedAtMillis, queueState.loadedAtMillis),
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                ZillitText(
                    text = meta,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
            // The diff, where there is one. An admin adjusting a department is
            // deciding about a change, and the change is the context for it.
            if (queue == ApprovalQueue.ProfileChanges) {
                request.changesAgainst(known).forEach { ChangeLine(it) }
            }
        }
    }
}

/**
 * Department, role, unit — the three the server seats someone from.
 *
 * Pre-filled with what the person asked for. The overwhelmingly common action
 * here is to read them and press Approve; the pickers exist for when one of
 * them is wrong.
 */
@Composable
private fun Placement(
    review: ReviewState,
    presets: PresetsState,
    onEvent: (ApprovalsEvent) -> Unit,
) {
    val department = presets.department(review.departmentId)
    // Whatever the request carried stays offerable even when the list did not
    // load, or a preset outage would silently blank someone's own choice.
    val departments = presets.departments.including(review.departmentId, review.request.departmentName)
    val roles = department?.roles ?: review.request.asRole(review.roleId)
    val units = presets.units.includingUnit(review.unitId, review.request.unitName)

    if (presets.isLoading) ReadingChoices()

    Field(label = "Department") {
        ZillitSelect(
            value = departments.firstOrNull { it?.id == review.departmentId },
            options = listOf<CrewDepartment?>(null) + departments.filterNotNull(),
            onSelect = { onEvent(ApprovalsEvent.Review.DepartmentChosen(it?.id)) },
            // Department, role and unit names are all translation keys.
            label = { it?.name?.localised() ?: NOT_SET },
            enabled = departments.size > 1,
            modifier = Modifier.width(FIELD_WIDTH),
        )
    }

    Field(
        label = "Role",
        // Says why it is empty rather than showing an inert picker: a role
        // list is empty because its department has none, not because the form
        // is broken.
        hint = if (department != null && department.roles.isEmpty()) {
            "${department.name.localised()} has no roles set up."
        } else {
            null
        },
    ) {
        ZillitSelect(
            value = roles.firstOrNull { it?.id == review.roleId },
            options = listOf<CrewRole?>(null) + roles.filterNotNull(),
            onSelect = { onEvent(ApprovalsEvent.Review.RoleChosen(it?.id)) },
            label = { it?.name?.localised() ?: NOT_SET },
            enabled = roles.isNotEmpty(),
            modifier = Modifier.width(FIELD_WIDTH),
        )
    }

    // Only when joining. A profile change never moves someone between units —
    // the server's change-request payload has no field for it.
    if (review.queue == ApprovalQueue.NewCrew) {
        Field(label = "Unit") {
            ZillitSelect(
                value = units.firstOrNull { it?.id == review.unitId },
                options = listOf<ProductionUnit?>(null) + units.filterNotNull(),
                onSelect = { onEvent(ApprovalsEvent.Review.UnitChosen(it?.id)) },
                label = { it?.name?.localised() ?: NOT_SET },
                enabled = units.size > 1,
                modifier = Modifier.width(FIELD_WIDTH),
            )
        }
    }

    ZillitCheckbox(
        checked = review.keepNamePrivate,
        onCheckedChange = { onEvent(ApprovalsEvent.Review.PrivacyChanged(it)) },
        label = "Keep their name off crew lists",
    )

    if (presets.failed) ChoicesUnavailable()
}

@Composable
private fun ReadingChoices() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitSpinner(size = SMALL_SPINNER)
        ZillitText(
            text = "Reading this production's departments…",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** The pickers hold only what was asked for; say so rather than look broken. */
@Composable
private fun ChoicesUnavailable() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(
            icon = ZillitIcons.Info,
            contentDescription = null,
            tint = ZillitTheme.colors.warning,
            size = HINT_GLYPH,
        )
        ZillitText(
            // Approving is still the right thing to be able to do: what the
            // person asked for is usually what they should get.
            text = "The production's departments could not be read. " +
                "Approving keeps what they asked for.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** A labelled row, the control right-aligned so the three line up. */
@Composable
private fun Field(label: String, hint: String? = null, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            ZillitText(text = label, style = ZillitTheme.typography.bodyMedium)
            hint?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        control()
    }
}

@Composable
private fun Actions(saving: Boolean, onEvent: (ApprovalsEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitButton(
            text = "Decline",
            variant = ButtonVariant.Danger,
            enabled = !saving,
            onClick = { onEvent(ApprovalsEvent.Review.Decline) },
        )
        Box(Modifier.weight(1f))

        if (saving) {
            // In the buttons' place rather than beside them: the dialog must
            // not resize while an admin's cursor is over the row.
            Box(Modifier.width(ACTION_WIDTH), contentAlignment = Alignment.Center) {
                ZillitSpinner()
            }
        } else {
            ZillitButton(
                text = "Cancel",
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(ApprovalsEvent.Review.Close) },
            )
            ZillitButton(
                text = "Approve",
                onClick = { onEvent(ApprovalsEvent.Review.Approve) },
            )
        }
    }
}

/**
 * The loaded list, with the request's own choice guaranteed present.
 *
 * A department that has since been renamed or removed is still the one this
 * person asked for, and dropping it from the picker would silently rewrite
 * their request the moment the form opened.
 */
private fun List<CrewDepartment>.including(id: String?, name: String?): List<CrewDepartment?> {
    if (id.isNullOrBlank() || any { it.id == id }) return this
    return this + CrewDepartment(id = id, name = name ?: UNKNOWN)
}

private fun List<ProductionUnit>.includingUnit(id: String?, name: String?): List<ProductionUnit?> {
    if (id.isNullOrBlank() || any { it.id == id }) return this
    return this + ProductionUnit(id = id, name = name ?: UNKNOWN)
}

/** The role a request carries, when its department's list never arrived. */
private fun PendingApproval.asRole(id: String?): List<CrewRole> =
    if (id.isNullOrBlank()) emptyList() else listOf(CrewRole(id, designationName ?: UNKNOWN))

private val ApprovalQueue.reviewTitle: String
    get() = when (this) {
        ApprovalQueue.NewCrew -> "Let them onto the production?"
        ApprovalQueue.ProfileChanges -> "Approve this change?"
    }

private const val NOT_SET = "Not set"
private const val UNKNOWN = "Their own choice"
private val DIALOG_WIDTH = 520.dp
private val FIELD_WIDTH = 240.dp
private val AVATAR = 40.dp
private val ACTION_WIDTH = 170.dp
private val SMALL_SPINNER = 14.dp
private val HINT_GLYPH = 14.dp
