package com.zillit.desktop.core.designsystem.component

/**
 * The hue a workflow status is drawn in.
 *
 * Named for the *feeling* rather than the colour so a status map reads as
 * intent — `StatusTone.Escalated` rather than `purple` — and so a theme can
 * move the hue without every finance module changing.
 *
 * Transcribed from the web's two status maps (`cardExpenses/lib/constants.js`
 * STATUS_COLORS and `cashExpenses/components/helpers.js` BATCH/FLOAT_STATUS_MAP),
 * which between them use eight hues.
 */
enum class StatusTone {
    /** Nothing is happening to this yet, or it is closed and unremarkable. */
    Neutral,

    /** Waiting on someone. The default for every "pending" state. */
    Pending,

    /** Moving through the workflow — coded, submitted, in audit. */
    Progress,

    /** Cleared its gate: approved, ready to post. */
    Ready,

    /** Finished and banked: posted, collected, completed. */
    Done,

    /** Refused or failed: rejected, cancelled, queried against. */
    Rejected,

    /** Taken out of the normal path: escalated, overridden, suspended. */
    Escalated,

    /** In flight between two parties: requested, in transit, ready to collect. */
    InTransit,
}
