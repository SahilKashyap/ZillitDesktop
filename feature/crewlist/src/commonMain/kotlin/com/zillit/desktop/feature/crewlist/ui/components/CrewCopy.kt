package com.zillit.desktop.feature.crewlist.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.zillit.desktop.core.localization.LabelDictionary
import com.zillit.desktop.core.localization.Labels

/**
 * The crew list's words: the production's translation when the dictionary
 * has the key, the web's English otherwise — the web's `tl(key, fallback)`,
 * which exists because a missing key used to leak into the UI as itself.
 *
 * `{tool_name}` is filled with "Crew List" or "Staff List", as the web's
 * `replaceLabelWithLocalTranslation` does.
 */
@Stable
class CrewCopy(
    private val dictionary: LabelDictionary = LabelDictionary.Empty,
    val toolName: String = "Crew List",
) {
    fun t(key: String, fallback: String): String =
        (dictionary.exact(key)?.takeIf { it.isNotBlank() } ?: fallback).replace("{tool_name}", toolName)

    /** A label key from the server — a designation, a unit — in display form. */
    fun label(key: String): String = if (key.isBlank()) "" else dictionary.translate(key)
}

/** Recomposes when a language lands, so nothing on screen keeps its fallback. */
@Composable
fun rememberCrewCopy(toolName: String): CrewCopy {
    val dictionary by Labels.dictionary.collectAsState()
    return remember(dictionary, toolName) { CrewCopy(dictionary, toolName) }
}
