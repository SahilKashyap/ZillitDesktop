package com.zillit.desktop.feature.home.calendar

/**
 * Where one event sits in a day column.
 *
 * All four values are fractions of the column, so the composable multiplies by
 * whatever height and width it ends up with and the maths stays free of `dp`.
 *
 * [lane] and [lanes] handle overlap: three events at the same hour each take a
 * third of the width and sit side by side, which is the whole reason a time
 * grid beats a list.
 */
data class PositionedEvent(
    val event: CalendarEvent,
    /** Fraction from the top of the day, 0..1. */
    val top: Float,
    /** Fraction of the day's height. Never zero — see [MIN_HEIGHT_FRACTION]. */
    val height: Float,
    val lane: Int,
    val lanes: Int,
) {
    val widthFraction: Float get() = 1f / lanes
    val leftFraction: Float get() = lane.toFloat() / lanes
}

/**
 * Lays out a day's events on a time grid.
 *
 * ## The overlap rule
 *
 * Events are placed left to right into the first lane whose last event has
 * already finished. Everything in a run of mutually-overlapping events then
 * shares the width equally — which is what FullCalendar does, and what makes a
 * morning of back-to-back meetings readable instead of a stack of covered
 * blocks.
 *
 * "A run" matters: two events at 09:00 and a third at 16:00 should not make the
 * afternoon one a third of the width. Lanes are counted per cluster, not per
 * day.
 *
 * All-day events are excluded — they have no position on a time axis and are
 * shown in a separate strip above the grid.
 */
fun layOutDay(
    events: List<CalendarEvent>,
    dayStartMillis: Long,
    dayEndMillis: Long,
): List<PositionedEvent> {
    val dayLength = (dayEndMillis - dayStartMillis).toFloat()
    if (dayLength <= 0f) return emptyList()

    val timed = events
        .filterNot { it.isAllDay }
        .map { it to it.clampedTo(dayStartMillis, dayEndMillis) }
        .filter { (_, span) -> span.first < span.second }
        .sortedWith(compareBy({ it.second.first }, { it.second.second }))

    return timed.clusters().flatMap { cluster ->
        val lanes = mutableListOf<Long>()

        cluster.map { (event, span) ->
            val (start, end) = span
            // The first lane free at this moment, or a new one.
            val lane = lanes.indexOfFirst { it <= start }.takeIf { it >= 0 } ?: lanes.size
            if (lane == lanes.size) lanes.add(end) else lanes[lane] = end

            Triple(event, span, lane)
        }.let { placed ->
            val laneCount = lanes.size
            placed.map { (event, span, lane) ->
                PositionedEvent(
                    event = event,
                    top = (span.first - dayStartMillis) / dayLength,
                    height = maxOf(
                        (span.second - span.first) / dayLength,
                        MIN_HEIGHT_FRACTION,
                    ),
                    lane = lane,
                    lanes = laneCount,
                )
            }
        }
    }
}

/**
 * Groups events into runs that overlap something else in the run.
 *
 * Without this, one lane count would apply to the whole day and a single long
 * meeting would squeeze every unrelated event into a sliver.
 */
private fun List<Pair<CalendarEvent, Pair<Long, Long>>>.clusters():
    List<List<Pair<CalendarEvent, Pair<Long, Long>>>> {
    val clusters = mutableListOf<MutableList<Pair<CalendarEvent, Pair<Long, Long>>>>()
    var clusterEnd = Long.MIN_VALUE

    forEach { entry ->
        val (start, end) = entry.second
        if (clusters.isEmpty() || start >= clusterEnd) {
            clusters += mutableListOf(entry)
            clusterEnd = end
        } else {
            clusters.last() += entry
            clusterEnd = maxOf(clusterEnd, end)
        }
    }

    return clusters
}

/**
 * The part of an event that falls inside this day.
 *
 * A multi-day event shows as a full-height block on each day it covers rather
 * than running off the bottom of the first one.
 */
private fun CalendarEvent.clampedTo(dayStart: Long, dayEnd: Long): Pair<Long, Long> {
    val end = if (endMillis > startMillis) endMillis else startMillis + MIN_DURATION_MILLIS
    return maxOf(startMillis, dayStart) to minOf(end, dayEnd)
}

/**
 * A zero-length event still has to be clickable.
 *
 * Roughly fifteen minutes of a day, which is about the smallest block that can
 * hold a legible title.
 */
private const val MIN_HEIGHT_FRACTION = 15f / (24f * 60f)

/** What an event with no end time is treated as lasting. */
private const val MIN_DURATION_MILLIS = 30L * 60L * 1000L
