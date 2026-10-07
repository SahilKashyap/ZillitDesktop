package com.zillit.desktop.feature.selectstills.domain

import com.zillit.desktop.core.strings.S

/**
 * Discard allowances, as the screens word them — the web's `lib/allowance.js`.
 *
 * An allowance is set per head-count SECTION — solo, 2, 3, 4, 5, and six or
 * more — keyed "1".."5" and "6+", as the service gives them:
 *   limits     { "1": 2, "3": 0 }             a section left out has no limit
 *   allowance  { "1": { limit, used, remaining }, … } for all six
 * Blank is NOT zero: no limit means nothing is capped; 0 means nothing may be
 * discarded in that section at all.
 */
val STILLS_SECTIONS: List<String> = listOf("1", "2", "3", "4", "5", "6+")

/** Which section a photo with this many people falls in; null with nobody in it. */
fun sectionOf(people: Int): String? = when {
    people <= 0 -> null
    people >= SIX -> "6+"
    else -> people.toString()
}

/** The string key of a section's name ("Solo", "2 people", … "6 or more"). */
fun sectionKey(section: String): String = when (section) {
    "6+" -> S.desktop_stk_section_6p
    "1" -> S.desktop_stk_section_1
    "2" -> S.desktop_stk_section_2
    "3" -> S.desktop_stk_section_3
    "4" -> S.desktop_stk_section_4
    "5" -> S.desktop_stk_section_5
    else -> S.desktop_stk_section_6p
}

private val SectionAllowance?.isCapped: Boolean get() = this != null && limit != null

/** How an allowance line reads: a string key, its numbers, and whether it is bad news. */
data class AllowanceWords(
    val key: String,
    val used: Int = 0,
    val limit: Int = 0,
    val remaining: Int = 0,
    val bad: Boolean = false,
)

/**
 * One section's allowance in words.
 *
 * Four cases, not two: a section capped at zero never allowed anything, and a
 * count above the cap (the cap was lowered later, or a discard passed to this
 * approver when somebody else undid theirs) is over, not "none left".
 */
fun allowanceWords(allowance: SectionAllowance?): AllowanceWords {
    if (!allowance.isCapped) return AllowanceWords(S.desktop_stk_allow_none)
    val limit = allowance?.limit ?: 0
    val used = allowance?.used ?: 0
    val remaining = allowance?.remaining ?: 0
    return when {
        used > limit -> AllowanceWords(S.desktop_stk_allow_over, used = used, limit = limit, bad = true)
        limit == 0 -> AllowanceWords(S.desktop_stk_allow_zero, limit = 0, bad = true)
        remaining == 0 -> AllowanceWords(S.desktop_stk_allow_spent, limit = limit, bad = true)
        else -> AllowanceWords(S.desktop_stk_allow_left, limit = limit, remaining = remaining)
    }
}

/**
 * Whether a discard here is certain to be refused, so the button can say so
 * before the click. Only a hint — the service decides (409
 * `still_kills_allowance_spent`). Never blocked when the row is already a
 * discard, or when somebody else already discarded the photo: agreeing with a
 * discard is free.
 */
fun discardBlocked(
    allowance: Map<String, SectionAllowance>?,
    section: String?,
    state: Decision,
    free: Boolean,
): Boolean {
    if (state == Decision.Rejected || free || section == null) return false
    val row = allowance?.get(section)
    return row.isCapped && (row?.remaining ?: 0) <= 0
}

/** Somebody other than this member already discarded the photo. */
fun discardIsFree(approvals: List<ApprovalRow>, memberId: String): Boolean =
    approvals.any { it.state == Decision.Rejected && it.memberId != memberId }

/** Limits as the service gives them → text for the six inputs. */
fun limitsToInputs(limits: Map<String, Int>?): Map<String, String> =
    STILLS_SECTIONS.associateWith { section -> limits?.get(section)?.toString() ?: "" }

/**
 * The six inputs → limits for the service, or null when one of them is not a
 * whole number from 0 up. An empty box is "no limit" and is left out.
 */
fun limitsFromInputs(inputs: Map<String, String>): Map<String, Int>? {
    val out = mutableMapOf<String, Int>()
    for (section in STILLS_SECTIONS) {
        val raw = inputs[section].orEmpty().trim()
        if (raw.isEmpty()) continue
        if (!raw.all { it.isDigit() }) return null
        out[section] = raw.toIntOrNull() ?: return null
    }
    return out
}

/** A short line for a list: "Solo 2 · 2 people 0", or null when nothing is capped. */
fun limitsSummary(limits: Map<String, Int>?, label: (String) -> String): String? {
    val parts = STILLS_SECTIONS.filter { limits?.get(it) != null }.map { "${label(sectionKey(it))} ${limits?.get(it)}" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** What a section's `used` counts are, for the allowance grid's "n used" lines. */
fun usedOf(allowance: Map<String, SectionAllowance>?): Map<String, Int> =
    allowance.orEmpty().mapValues { (_, row) -> row.used }

private const val SIX = 6
