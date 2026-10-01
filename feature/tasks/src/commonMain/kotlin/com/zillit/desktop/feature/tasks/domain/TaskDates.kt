package com.zillit.desktop.feature.tasks.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/**
 * Due dates are calendar days (`"YYYY-MM-DD"`), not instants, so "today" is the
 * viewer's today in every timezone. Callers pass today in rather than reading a
 * clock here, which is what makes these testable.
 */

private fun String?.toDay(): LocalDate? = this?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** Whole days from [today] to [due] (negative = overdue), or null when undated. */
fun daysUntil(due: String?, today: LocalDate): Int? = due.toDay()?.let { today.daysUntil(it) }

/** My Tasks grouping. Undated tasks are [Later]. */
enum class DueBucket { Overdue, Today, Week, Later }

fun dueBucket(due: String?, today: LocalDate): DueBucket {
    val n = daysUntil(due, today) ?: return DueBucket.Later
    return when {
        n < 0 -> DueBucket.Overdue
        n == 0 -> DueBucket.Today
        n <= WEEK -> DueBucket.Week
        else -> DueBucket.Later
    }
}

/** What a card's due chip says, before it is worded in the reader's language. */
sealed interface DueLabel {
    data object None : DueLabel
    data object Today : DueLabel
    data object Tomorrow : DueLabel
    data object Yesterday : DueLabel

    /** Inside the coming week: "Tue, Oct 6". */
    data class Soon(val date: LocalDate) : DueLabel

    /** Anything else: "Oct 20". */
    data class On(val date: LocalDate) : DueLabel
}

fun dueLabel(due: String?, today: LocalDate): DueLabel {
    val date = due.toDay() ?: return DueLabel.None
    return when (val n = today.daysUntil(date)) {
        0 -> DueLabel.Today
        1 -> DueLabel.Tomorrow
        -1 -> DueLabel.Yesterday
        else -> if (n in 2 until WEEK) DueLabel.Soon(date) else DueLabel.On(date)
    }
}

enum class DueTone { None, Late, Today }

/** The tone of a due label; closed (done or cancelled) tasks are never late. */
fun dueTone(task: Task, today: LocalDate): DueTone {
    if (task.status.isClosed) return DueTone.None
    val n = daysUntil(task.dueDate, today) ?: return DueTone.None
    return when {
        n < 0 -> DueTone.Late
        n == 0 -> DueTone.Today
        else -> DueTone.None
    }
}

private const val WEEK = 7
