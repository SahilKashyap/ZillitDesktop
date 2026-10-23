package com.zillit.desktop.feature.scriptsupervision.domain

import com.zillit.desktop.core.designsystem.component.StatusTone

/**
 * The module's own enums — the web's `scriptSupervision/constants.js`.
 *
 * These are the service's values, written exactly as it stores them; what the
 * reader sees is the translated label beside each.
 */
object ScriptSupervision {

    /** The tool's backend identifier (zillit-libs preset). */
    const val TOOL: String = ScriptSupervisionViewer.TOOL

    val SCENE_STATUSES: List<String> = listOf("pending", "in_progress", "completed", "omitted", "postponed")

    val INT_EXT: List<String> = listOf("INT", "EXT", "INT/EXT", "EXT/INT")

    val DAY_NIGHT: List<String> = listOf(
        "DAY",
        "NIGHT",
        "DAWN",
        "DUSK",
        "MORNING",
        "AFTERNOON",
        "EVENING",
        "CONTINUOUS",
    )

    /**
     * Take flags, shown as tags and edited as tick boxes, in this order.
     *
     * The service stores each as its own boolean rather than one status, since
     * a take can be several of these at once — circled AND a pickup.
     */
    val TAKE_FLAGS: List<String> = listOf(
        "is_circled",
        "is_printed",
        "is_directors_select",
        "is_ng",
        "is_mos",
        "is_false_start",
        "is_pickup",
    )

    /** A scene as one line: its number and its heading. */
    fun sceneLabel(scene: Rec?): String =
        listOf(scene?.str("scene_number").orEmpty(), scene?.str("scene_name").orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

/**
 * How a scene's status is drawn — by meaning, not by the old module's palette
 * (the web's `STATUS_TAG`).
 *
 * An unknown status reads as neutral rather than as nothing: the service may
 * add one, and a blank cell says less than a quiet pill.
 */
fun sceneStatusTone(status: String): StatusTone = when (status) {
    "in_progress" -> StatusTone.Progress
    "completed" -> StatusTone.Done
    "postponed" -> StatusTone.Escalated
    else -> StatusTone.Neutral
}

/**
 * How a look's stage is drawn — the web's `LOOK_STATUS_TAG`.
 *
 * `broken` is the one that has to shout: a look recorded as broken is
 * continuity that will not match on the day, which is the whole reason the
 * script supervisor keeps these.
 */
fun lookStatusTone(status: String): StatusTone = when (status) {
    "established" -> StatusTone.Progress
    "active" -> StatusTone.Done
    "broken" -> StatusTone.Escalated
    // `planned` and `wrapped` are both quiet: nothing to do about either.
    else -> StatusTone.Neutral
}
