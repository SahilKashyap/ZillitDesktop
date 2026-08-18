package com.zillit.desktop.feature.recce.domain

/**
 * The weather line is ONE string on the wire — `"12°C – 18°C, light cloud"`.
 * The web's form splits it into low / high / unit / conditions and joins it
 * back; this is that split and join, so an edited recce round-trips the
 * string the web wrote (en dash, spaced) and vice versa.
 */
data class Weather(
    val low: String = "",
    val high: String = "",
    /** `C` or `F`. */
    val unit: String = "C",
    val conditions: String = "",
) {
    fun compose(): String {
        val u = "°$unit"
        val lo = low.trim()
        val hi = high.trim()
        val temp = when {
            lo.isNotEmpty() && hi.isNotEmpty() -> "$lo$u – $hi$u"
            lo.isNotEmpty() -> "$lo$u"
            hi.isNotEmpty() -> "$hi$u"
            else -> ""
        }
        return listOf(temp, conditions.trim()).filter { it.isNotEmpty() }.joinToString(", ")
    }

    companion object {
        private val RANGE = Regex(
            """^(-?\d+(?:\.\d+)?)\s*°?\s*([CF])?\s*(?:–|-|to)\s*(-?\d+(?:\.\d+)?)\s*°?\s*([CF])?\s*,?\s*(.*)$""",
            RegexOption.IGNORE_CASE,
        )
        private val SINGLE = Regex("""^(-?\d+(?:\.\d+)?)\s*°?\s*([CF])?\s*,?\s*(.*)$""", RegexOption.IGNORE_CASE)

        fun parse(text: String): Weather {
            val s = text.trim()
            if (s.isEmpty()) return Weather()
            RANGE.matchEntire(s)?.let { m ->
                val g = m.groupValues
                val unit = g[UNIT_AFTER].ifEmpty { g[UNIT_BEFORE] }.ifEmpty { "C" }.uppercase()
                return Weather(low = g[LOW], high = g[HIGH], unit = unit, conditions = g[CONDITIONS].trim())
            }
            SINGLE.matchEntire(s)?.let { m ->
                val g = m.groupValues
                return Weather(low = g[1], unit = g[2].ifEmpty { "C" }.uppercase(), conditions = g[3].trim())
            }
            return Weather(conditions = s)
        }

        // Capture groups of RANGE: low, unit-after-low, high, unit-after-high, conditions.
        private const val LOW = 1
        private const val UNIT_BEFORE = 2
        private const val HIGH = 3
        private const val UNIT_AFTER = 4
        private const val CONDITIONS = 5
    }
}
