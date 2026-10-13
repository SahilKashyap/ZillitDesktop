package com.zillit.desktop.feature.purchaseorder.domain

/**
 * A nominal the chart does not know goes to the server as `[[code]]`, which is
 * how the web's `wrapNominal` (`lib/coa.js`) tells it to create that code for
 * the production rather than store a stray string.
 *
 * Applied only when the save body is built, never to the line on screen, so
 * every reader keeps seeing the clean code. Returns the trimmed code unchanged
 * when it is empty, when [chart] is empty (not read yet — a load race must not
 * flag every existing code as new), or when the chart has it, exactly or in a
 * different case.
 */
fun wrapNominal(code: String, chart: Set<String>): String {
    val trimmed = code.trim()
    if (trimmed.isEmpty() || chart.isEmpty()) return trimmed
    if (trimmed in chart || chart.any { it.equals(trimmed, ignoreCase = true) }) return trimmed
    return "[[$trimmed]]"
}
