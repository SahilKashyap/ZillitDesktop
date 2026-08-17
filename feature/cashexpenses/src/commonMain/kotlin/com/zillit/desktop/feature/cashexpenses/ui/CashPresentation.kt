package com.zillit.desktop.feature.cashexpenses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.Lifecycle

/**
 * How the cash domain is rendered.
 *
 * Kept apart from the pages so a status has exactly one colour across nine
 * screens, and so the mapping can be read (and corrected) in one place rather
 * than found in nine.
 */

/** The pill hue for a batch status. Mirrors the web's `BATCH_STATUS_MAP`. */
val BatchStatus.tone: StatusTone
    get() = when (this) {
        BatchStatus.Pending, BatchStatus.Unknown -> StatusTone.Pending
        BatchStatus.Coding -> StatusTone.Escalated
        BatchStatus.Coded, BatchStatus.InAudit, BatchStatus.UnderReview -> StatusTone.Progress
        BatchStatus.AwaitingApproval -> StatusTone.Pending
        BatchStatus.ReadyToPost -> StatusTone.Ready
        BatchStatus.Escalated -> StatusTone.Escalated
        BatchStatus.Posted -> StatusTone.Done
        BatchStatus.Queried -> StatusTone.Pending
        BatchStatus.Rejected -> StatusTone.Rejected
        // Amber for an accountant (it is an exception they made) and green for
        // everyone else (downstream it simply means ready). The label splits
        // the same way — see BatchStatus.label.
        BatchStatus.AcctOverride -> StatusTone.Ready
    }

/** The pill hue for a float status. Mirrors `FLOAT_STATUS_MAP`. */
val FloatStatus.tone: StatusTone
    get() = when (this) {
        FloatStatus.AwaitingApproval, FloatStatus.Unknown -> StatusTone.Pending
        FloatStatus.Approved -> StatusTone.Progress
        FloatStatus.AcctOverride -> StatusTone.Pending
        FloatStatus.ReadyToCollect -> StatusTone.InTransit
        FloatStatus.Collected, FloatStatus.Active -> StatusTone.Ready
        FloatStatus.Spending -> StatusTone.Pending
        FloatStatus.Spent -> StatusTone.Escalated
        FloatStatus.PendingReturn -> StatusTone.Rejected
        FloatStatus.Cancelled, FloatStatus.Rejected -> StatusTone.Rejected
        FloatStatus.Closed -> StatusTone.Neutral
    }

@Composable
fun BatchStatusPill(status: BatchStatus, accountant: Boolean, modifier: Modifier = Modifier) {
    ZillitStatusPill(
        label = status.label(accountant),
        tone = if (accountant && status == BatchStatus.AcctOverride) StatusTone.Pending else status.tone,
        dot = true,
        modifier = modifier,
    )
}

@Composable
fun FloatStatusPill(status: FloatStatus, modifier: Modifier = Modifier) {
    ZillitStatusPill(label = status.label, tone = status.tone, dot = true, modifier = modifier)
}

/** `£1,240.00`, in the record's own currency rather than the viewer's. */
fun money(amount: Double?, currency: String?): String = Money.format(amount, currency)

fun ClaimBatch.formattedTotal(): String = money(totalGross, currency)

fun CashFloat.formattedBalance(): String = money(balance, currency)

fun date(millis: Long?): String = EpochDate.date(millis).ifEmpty { "—" }

/**
 * The five-stage progress bar shown on a batch.
 *
 * A queried or rejected batch is drawn as a single amber or red bar rather
 * than a partly-filled line: it is not "40% of the way through", it has left
 * the pipeline and is waiting on the person who submitted it, and drawing it
 * as progress tells them the opposite.
 */
@Composable
fun LifecycleBar(status: BatchStatus, modifier: Modifier = Modifier) {
    val lifecycle = Lifecycle.of(status)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        if (lifecycle is Lifecycle.NeedsAction) {
            ZillitStatusPill(
                label = if (lifecycle.reason == BatchStatus.Rejected) {
                    "Rejected — resubmit required"
                } else {
                    "Queried — action needed"
                },
                tone = lifecycle.reason.tone,
                dot = true,
            )
            return@Column
        }

        val activeIndex = when (lifecycle) {
            is Lifecycle.At -> lifecycle.index
            Lifecycle.Complete -> Lifecycle.STAGES.size
            is Lifecycle.NeedsAction -> -1
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            Lifecycle.STAGES.forEachIndexed { index, _ ->
                ZillitMeter(
                    fraction = if (index < activeIndex) 1f else if (index == activeIndex) HALF else 0f,
                    tone = if (index < activeIndex) StatusTone.Done else StatusTone.Progress,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lifecycle.STAGES.forEachIndexed { index, stage ->
                ZillitText(
                    text = stage.label,
                    style = ZillitTheme.typography.labelSmall,
                    color = if (index <= activeIndex) {
                        ZillitTheme.colors.textPrimary
                    } else {
                        ZillitTheme.colors.textMuted
                    },
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private const val HALF = 0.5f
