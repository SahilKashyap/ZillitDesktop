package com.zillit.desktop.feature.costreport.domain.analytics

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * A forecast made ready to draw — the web's `forecastF`.
 *
 * Every amount is rescaled once to thousands or millions ([inMillions]) so
 * the projection's axis and the rail agree with each other; the display
 * strings stay in full currency. The variance's sign decides its tone and
 * wording, and the week labels run across the whole schedule.
 */
data class ForecastView(
    val tone: String,
    val inMillions: Boolean,
    /** The current shoot week, one-based. */
    val currentWeek: Int,
    val totalWeeks: Int,
    val wrap: String,
    val budget: Double,
    val actual: Double,
    val committed: Double,
    val etc: Double,
    val efc: Double,
    val budgetText: String,
    val actualText: String,
    val committedText: String,
    val etcText: String,
    val efcText: String,
    val varianceText: String,
    /** Blank when there is no budget to measure against. */
    val variancePercentText: String,
    val over: Boolean,
    val cumulative: List<Double>,
    val projection: List<Double>,
    val labels: List<String>,
    val driver: String?,
) {
    val varianceTone: String get() = if (over) "red" else "green"
    val varianceLabel: String get() = if (over) "over budget" else "under budget"

    /** The week the projection ends on, as an index into [labels]. */
    val projectionEnd: Int
        get() = if (projection.isEmpty()) currentWeek - 1 else minOf(labels.size - 1, currentWeek - 1 + projection.size - 1)

    companion object {
        private const val MILLION = 1_000_000.0
        private const val THOUSAND = 1_000.0
        private const val PERCENT = 100.0
        private const val TENTHS = 10.0
        private const val MINUS = "−"

        fun of(data: ForecastData, currency: String?): ForecastView {
            val efc = data.efc ?: (data.actual + data.committed + data.etc)
            val magnitude = (listOf(abs(efc), abs(data.budget)) + data.cum.map(::abs) + data.proj.map(::abs)).max()
            val inMillions = magnitude >= MILLION
            val divisor = if (inMillions) MILLION else THOUSAND
            val variance = data.variance ?: (efc - data.budget)
            val over = variance > 0
            val sign = if (over) "+" else MINUS
            val total = data.total?.takeIf { it > 0 }
                ?: (data.cum.size + max(data.proj.size - 1, 0)).takeIf { it > 0 }
                ?: 1
            val percent = if (data.budget == 0.0) {
                ""
            } else {
                "$sign${fixedOne(abs(variance) / data.budget * PERCENT)}%"
            }
            return ForecastView(
                tone = data.tone?.takeIf { it.isNotBlank() } ?: "amber",
                inMillions = inMillions,
                currentWeek = data.cur?.takeIf { it > 0 } ?: data.cum.size,
                totalWeeks = total,
                wrap = data.wrap.orEmpty(),
                budget = data.budget / divisor,
                actual = data.actual / divisor,
                committed = data.committed / divisor,
                etc = data.etc / divisor,
                efc = efc / divisor,
                budgetText = AnalyticsFormat.money(data.budget, currency),
                actualText = AnalyticsFormat.money(data.actual, currency),
                committedText = AnalyticsFormat.money(data.committed, currency),
                etcText = AnalyticsFormat.money(data.etc, currency),
                efcText = AnalyticsFormat.money(efc, currency),
                varianceText = sign + AnalyticsFormat.money(abs(variance), currency),
                variancePercentText = percent,
                over = over,
                cumulative = data.cum.map { it / divisor },
                projection = data.proj.map { it / divisor },
                labels = List(total) { "W${it + 1}" },
                driver = data.driver?.takeIf { it.isNotBlank() },
            )
        }

        /** `toFixed(1)`. */
        private fun fixedOne(value: Double): String {
            val tenths = floor(value * TENTHS + 0.5).toLong()
            return "${tenths / TENTHS.toLong()}.${tenths % TENTHS.toLong()}"
        }
    }
}
