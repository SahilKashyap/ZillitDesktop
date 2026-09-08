package com.zillit.desktop

import com.zillit.desktop.core.appupdate.FirebaseRemoteFlags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Whether Line 3 is offered on a production.
 *
 * The phones' rule, from `AppHelper.isLine3EnabledForCurrentProject`: remote
 * config's `line_three_enabled_in` is a JSON array of production ids, or the
 * word `All`; absent or empty means hidden. Deliberately not under any
 * "calling enabled" master switch — Line 3 is rolled out production by
 * production, and this is the roll-out list.
 */
internal class LineThreeGate(
    private val flags: FirebaseRemoteFlags,
    /** Whether the install has the backend configured at all — no socket URL, no Line 3 whatever the list says. */
    private val configured: Boolean,
) {
    @Volatile private var enabledIn: List<String>? = null

    /** Refreshes the list; called once a production is open. */
    suspend fun refresh() {
        if (!configured) return
        enabledIn = parse(flags.value(KEY))
    }

    /** Whether to offer Line 3 on [projectId], from the last refresh. */
    fun isEnabledFor(projectId: String?): Boolean {
        if (!configured) return false
        val list = enabledIn ?: return false
        return list.contains(ALL) || (projectId != null && list.contains(projectId))
    }

    fun clear() {
        enabledIn = null
    }

    companion object {
        const val KEY = "line_three_enabled_in"
        private const val ALL = "All"

        /** The raw flag: a JSON array of ids or `All`; anything unreadable is nothing. */
        fun parse(raw: String?): List<String> {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
            val array = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull()
                ?: return if (text.equals(ALL, ignoreCase = true)) listOf(ALL) else emptyList()
            return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                .map { if (it.equals(ALL, ignoreCase = true)) ALL else it }
        }
    }
}
