package com.zillit.desktop.feature.settings.approvals

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * An approval queue: who is waiting, what they asked for, and two buttons.
 *
 * One screen for both queues. They differ in what a row says the person wants —
 * a department and a role for someone joining, a list of changes for someone
 * already here — and in nothing else, and two screens would be two places to
 * fix the next thing wrong with a row.
 *
 * The decision buttons sit on the row rather than behind a detail page. Both
 * phone clients offer both: a tappable row that opens a form, *and* approve and
 * decline on the row itself, because an admin clearing a morning's requests is
 * not reading each one. The form — where an admin changes someone's department
 * before letting them in — is the part not built here yet.
 */
@Composable
fun ApprovalsScreen(
    queue: ApprovalQueue,
    state: ApprovalsUiState,
    onEvent: (ApprovalsEvent) -> Unit,
    onBack: () -> Unit,
    /** What the crew list holds for a person, for the change diff. */
    known: (String) -> KnownCrewMember?,
    modifier: Modifier = Modifier,
) {
    val queueState = state[queue]

    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth().fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Header(queue, queueState, onBack, onEvent)

                queueState.outcome?.let { outcome ->
                    Strip(
                        text = outcome,
                        tint = ZillitTheme.colors.successSoft,
                        icon = ZillitIcons.Check,
                        iconTint = ZillitTheme.colors.success,
                        onDismiss = { onEvent(ApprovalsEvent.DismissOutcome(queue)) },
                    )
                }
                queueState.error?.let { error ->
                    Strip(
                        text = error,
                        tint = ZillitTheme.colors.dangerSoft,
                        icon = ZillitIcons.Info,
                        iconTint = ZillitTheme.colors.danger,
                        onDismiss = null,
                    )
                }

                // Absent until there is enough to be worth filtering. A search
                // box over three rows is furniture.
                if (queueState.items.size >= SEARCH_THRESHOLD) {
                    ZillitSearchField(
                        value = queueState.query,
                        onValueChange = { onEvent(ApprovalsEvent.SearchChanged(queue, it)) },
                        placeholder = "Search by name, department or role",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Body(queue, queueState, onEvent, known)
            }
        }

        DeclineDialog(queue, queueState, onEvent)
        ApprovalReviewDialog(state = state, onEvent = onEvent, known = known)
    }
}

@Composable
private fun Header(
    queue: ApprovalQueue,
    state: ApprovalQueueState,
    onBack: () -> Unit,
    onEvent: (ApprovalsEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIconButton(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = "Back to admin settings",
            onClick = onBack,
        )
        Box(
            Modifier
                .width(TITLE_ACCENT_WIDTH)
                .height(TITLE_ACCENT_HEIGHT)
                .clip(ZillitTheme.shapes.pill)
                .background(ZillitTheme.colors.accent),
        )
        Column(Modifier.weight(1f)) {
            ZillitText(text = queue.title, style = ZillitTheme.typography.displayLarge)
            ZillitText(
                text = queue.subtitle(state),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitButton(
            text = "Refresh",
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            enabled = !state.isLoading,
            onClick = { onEvent(ApprovalsEvent.Refresh(queue)) },
        )
    }
}

@Composable
private fun Body(
    queue: ApprovalQueue,
    state: ApprovalQueueState,
    onEvent: (ApprovalsEvent) -> Unit,
    known: (String) -> KnownCrewMember?,
) {
    when {
        // Only on the first read. A refresh keeps the list on screen, because
        // an admin mid-decision should not have it replaced by a spinner.
        state.isLoading && state.items.isEmpty() -> Centred { ZillitSpinner() }

        state.items.isEmpty() -> Centred {
            EmptyQueue(queue, failed = state.error != null)
        }

        state.isFilteredEmpty -> Centred {
            ZillitText(
                text = "Nobody here matches “${state.query}”.",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        else -> {
            val listState = rememberLazyListState()
            ZillitLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = ZillitTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                items(state.visible, key = { it.id }) { request ->
                    ApprovalCard(
                        queue = queue,
                        request = request,
                        known = known(request.userId),
                        isDeciding = request.id in state.deciding,
                        loadedAtMillis = state.loadedAtMillis,
                        onReview = { onEvent(ApprovalsEvent.Review.Open(queue, request.id)) },
                        onApprove = { onEvent(ApprovalsEvent.Approve(queue, request.id)) },
                        onDecline = { onEvent(ApprovalsEvent.AskDecline(queue, request.id)) },
                    )
                }
            }
        }
    }
}

/**
 * One person, and the decision.
 *
 * The buttons stay put while a decision is in flight — replaced by a spinner in
 * the same place, so the row does not resize under the cursor and the next one
 * does not jump up to meet a click meant for this one.
 *
 * The body of the row opens the review form; the two buttons decide on the spot.
 * Both, because an admin clearing a morning's requests wants the buttons, and
 * one who needs to move somebody's department wants the form — and neither
 * should have to go the other's way round.
 */
@Composable
private fun ApprovalCard(
    queue: ApprovalQueue,
    request: PendingApproval,
    known: KnownCrewMember?,
    isDeciding: Boolean,
    loadedAtMillis: Long,
    onReview: () -> Unit,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(if (hovered) ZillitTheme.colors.surfaceHover else ZillitTheme.colors.surface)
            .border(
                HAIRLINE,
                if (hovered) ZillitTheme.colors.borderStrong else ZillitTheme.colors.border,
                ZillitTheme.shapes.medium,
            )
            .hoverable(interaction, enabled = !isDeciding)
            .clickable(enabled = !isDeciding, onClickLabel = "Review this request", onClick = onReview)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitAvatar(name = request.displayName, size = AVATAR)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = request.displayName,
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                    // Yields before the tag does. Without this the name takes
                    // what it wants and a long one truncates the tag instead,
                    // which is the half of the row that cannot be guessed.
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Said on the row, because approving it is agreeing to it: this
                // person will not be named to the rest of the unit.
                if (request.keepNamePrivate) ZillitTag("Name hidden", tone = TagTone.Neutral)
            }
            RequestDetail(queue, request, known)
            Meta(request, loadedAtMillis)
        }

        Decision(isDeciding = isDeciding, onApprove = onApprove, onDecline = onDecline)
    }
}

/** What the person is asking for — a placement, or a set of changes. */
@Composable
private fun RequestDetail(queue: ApprovalQueue, request: PendingApproval, known: KnownCrewMember?) {
    val changes = if (queue == ApprovalQueue.ProfileChanges) request.changesAgainst(known) else emptyList()

    if (changes.isEmpty()) {
        val line = request.roleLine.ifBlank {
            if (queue == ApprovalQueue.ProfileChanges) {
                // The server sends no before-and-after, so with nobody to
                // compare against there is genuinely nothing to show but this.
                "Asked for a change to their profile."
            } else {
                "No department or role chosen."
            }
        }
        ZillitText(
            text = line,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            // Ellipsised rather than wrapped: three dot-separated fragments
            // reflowing into a ragged block is harder to scan than a clipped
            // line, and the card's job is to be scanned.
            maxLines = 1,
        )
        return
    }

    changes.forEach { change -> ChangeLine(change) }
}

/**
 * "Department   Camera → Lighting", on one line.
 *
 * The before, the arrow and the after are one text rather than three laid out
 * beside each other: as separate children the last one gets whatever width is
 * left, which on a narrow window was a single character column and a name
 * spelled downwards. One string cannot do that — it ellipsises instead.
 */
@Composable
internal fun ChangeLine(change: ProfileChange) {
    val colors = ZillitTheme.colors
    val muted = SpanStyle(color = colors.textMuted)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = change.label,
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            maxLines = 1,
            modifier = Modifier.width(CHANGE_LABEL_WIDTH),
        )
        ZillitText(
            text = buildAnnotatedString {
                withStyle(muted) { append(change.from) }
                withStyle(muted) { append(ARROW) }
                // The only part in full-strength text: what the admin is being
                // asked to agree to.
                withStyle(SpanStyle(color = colors.textPrimary)) { append(change.to) }
            },
            style = ZillitTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Address and how long they have been waiting. */
@Composable
private fun Meta(request: PendingApproval, loadedAtMillis: Long) {
    val line = listOfNotNull(
        request.email,
        waitedFor(request.requestedAtMillis, loadedAtMillis),
    ).joinToString(" · ")

    if (line.isBlank()) return
    ZillitText(
        text = line,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
    )
}

@Composable
private fun Decision(isDeciding: Boolean, onApprove: () -> Unit, onDecline: () -> Unit) {
    if (isDeciding) {
        Box(Modifier.width(DECISION_WIDTH), contentAlignment = Alignment.Center) { ZillitSpinner() }
        return
    }
    Row(
        modifier = Modifier.width(DECISION_WIDTH),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
    ) {
        ZillitButton(
            text = "Decline",
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            onClick = onDecline,
        )
        ZillitButton(
            text = "Approve",
            variant = ButtonVariant.Primary,
            size = ButtonSize.Small,
            onClick = onApprove,
        )
    }
}

@Composable
private fun DeclineDialog(
    queue: ApprovalQueue,
    state: ApprovalQueueState,
    onEvent: (ApprovalsEvent) -> Unit,
) {
    val request = state.confirming

    ZillitDialogShell(
        title = "Decline this request?",
        subtitle = request?.displayName.orEmpty(),
        icon = ZillitIcons.User,
        visible = request != null,
        onDismiss = { onEvent(ApprovalsEvent.DismissDecline(queue)) },
        width = DIALOG_WIDTH,
    ) {
        ZillitText(
            text = when (queue) {
                ApprovalQueue.NewCrew ->
                    "They will not be let onto this project. Nothing tells them why, and " +
                        "they can ask again with the project code."

                ApprovalQueue.ProfileChanges ->
                    "Their profile stays as it is. They can ask for the change again."
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Cancel",
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(ApprovalsEvent.DismissDecline(queue)) },
            )
            ZillitButton(
                text = "Decline",
                variant = ButtonVariant.Danger,
                onClick = { onEvent(ApprovalsEvent.ConfirmDecline(queue)) },
            )
        }
    }
}

@Composable
private fun EmptyQueue(queue: ApprovalQueue, failed: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(
            icon = if (failed) ZillitIcons.Info else ZillitIcons.Check,
            contentDescription = null,
            tint = if (failed) ZillitTheme.colors.textMuted else ZillitTheme.colors.success,
            size = EMPTY_GLYPH,
        )
        ZillitText(
            // Distinguishes an empty queue from a queue that could not be read —
            // the strip above carries the reason, and this must not read as
            // "all clear" when nothing was fetched.
            text = if (failed) "This queue could not be loaded." else queue.emptyMessage,
            style = ZillitTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Strip(
    text: String,
    tint: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    onDismiss: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(tint)
            .padding(ZillitTheme.spacing.md),
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
                contentDescription = "Dismiss",
                onClick = onDismiss,
                tint = ZillitTheme.colors.textMuted,
            )
        }
    }
}

@Composable
private fun Centred(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private val ApprovalQueue.title: String
    get() = when (this) {
        ApprovalQueue.NewCrew -> "Approve new crew"
        ApprovalQueue.ProfileChanges -> "Approve profile changes"
    }

private val ApprovalQueue.emptyMessage: String
    get() = when (this) {
        ApprovalQueue.NewCrew -> "Nobody is waiting to join."
        ApprovalQueue.ProfileChanges -> "No profile changes are waiting."
    }

/** Says how many, because that is the only reason to open this page. */
private fun ApprovalQueue.subtitle(state: ApprovalQueueState): String = when {
    !state.hasLoaded -> "Reading the queue…"
    state.items.isEmpty() -> "Nothing waiting."
    state.items.size == 1 -> "1 person waiting."
    else -> "${state.items.size} people waiting."
}

/**
 * How long someone has been waiting, in the coarsest useful unit.
 *
 * A queue's age is the thing an admin acts on — a request from this morning and
 * one from three weeks ago are different problems — and a timestamp would make
 * them do the arithmetic. Measured against when the list was read, so it cannot
 * drift into the future while the window sits open.
 */
internal fun waitedFor(requestedAtMillis: Long?, loadedAtMillis: Long): String? {
    if (requestedAtMillis == null || loadedAtMillis <= 0) return null
    val elapsed = loadedAtMillis - requestedAtMillis
    // A request from the future is a clock disagreement, not information.
    if (elapsed < 0) return null

    val days = elapsed / DAY_MILLIS
    return when {
        days < 1 -> "asked today"
        days == 1L -> "waiting 1 day"
        days < DAYS_IN_WEEK * 2 -> "waiting $days days"
        else -> "waiting ${days / DAYS_IN_WEEK} weeks"
    }
}

private const val DAY_MILLIS = 86_400_000L
private const val DAYS_IN_WEEK = 7
private const val SEARCH_THRESHOLD = 4
private val CONTENT_MAX_WIDTH = 820.dp
private val TITLE_ACCENT_WIDTH = 4.dp
private val TITLE_ACCENT_HEIGHT = 40.dp
private val AVATAR = 40.dp
private const val ARROW = "  \u2192  "
private val CHANGE_LABEL_WIDTH = 76.dp
private val DECISION_WIDTH = 170.dp
private val DIALOG_WIDTH = 420.dp
private val EMPTY_GLYPH = 28.dp
private val STRIP_GLYPH = 16.dp
private val HAIRLINE = 1.dp
