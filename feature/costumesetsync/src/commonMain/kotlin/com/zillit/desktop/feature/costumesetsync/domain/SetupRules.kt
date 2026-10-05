package com.zillit.desktop.feature.costumesetsync.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The first-run rules (the web's `lib/setup.js`). "Not set up" and who may set up live on [SyncProject]
 * (`notSetUp`, `canSetUp`); this holds the form's own: whether the type is asked, and what Create saves.
 */
object SetupRules {
    /** The project's own type, when Zillit already says it — then the type isn't asked. */
    val KNOWN_TYPES = listOf("FEATURE", "EPISODIC")

    /** The first-run form asks Feature / TV Series only when Zillit doesn't already say it. */
    fun needsType(type: String?): Boolean = type !in KNOWN_TYPES

    /** The six dates the production record holds. */
    val DATE_KEYS = listOf("start_date", "end_date", "prep_start_date", "prep_end_date", "wrap_date", "prep_wrap_date")

    /**
     * What Create saves before the script review opens (`PATCH /projects`): the [type] when it was asked, and all
     * six dates (epoch ms at local midnight, 0 = not set) only when at least one was entered, since the dates are
     * optional and a form left blank saves none. [dates] maps a date key to a `YYYY-MM-DD` day (or empty).
     */
    fun payload(type: String?, dates: Map<String, String>): JsonObject = buildJsonObject {
        if (!type.isNullOrEmpty()) put("type", JsonPrimitive(type))
        if (DATE_KEYS.any { DayKeys.parse(dates[it].orEmpty()) != null }) {
            DATE_KEYS.forEach { put(it, JsonPrimitive(DayKeys.toMs(dates[it].orEmpty()))) }
        }
    }
}
