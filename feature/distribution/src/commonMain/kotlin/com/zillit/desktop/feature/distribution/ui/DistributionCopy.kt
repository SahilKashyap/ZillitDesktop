package com.zillit.desktop.feature.distribution.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.zillit.desktop.core.localization.LabelDictionary
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The grid's words: the production's translation when the dictionary has the
 * key, the web's English otherwise — `getLocalStaticData(key)` with the
 * `en.js` value as the fallback, so a missing key never leaks as itself.
 */
@Stable
class DistributionCopy(private val dictionary: LabelDictionary = LabelDictionary.Empty) {

    fun t(key: String, fallback: String): String =
        dictionary.exact(key)?.takeIf { it.isNotBlank() } ?: fallback

    /** A label key from the server — a unit, a designation — in display form. */
    fun label(key: String): String = if (key.isBlank()) "" else dictionary.translate(key)

    val title: String get() = t("Document_Distribution", str(S.distributon_list))
    val search: String get() = t("Search", str(S.search))
    val home: String get() = t("Home", str(S.home))
    val tools: String get() = t("Tools", str(S.tools))
    val filterUnits: String get() = t("Filter Units or Tools", str(S.desktop_dist_filter_units_or_tools))
    val filterExternal: String get() = t("Filter External Users", str(S.desktop_dist_filter_external_users))
    val user: String get() = t("User", str(S.user_label))
    val outsider: String get() = t("Outsider", str(S.outsider))
    val noDetails: String get() = t("No_details", str(S.desktop_no_details))
    val note: String get() = t("NOTE", str(S.note_label))
    val noteBody: String get() = t("distribution_list_note", str(S.desktop_dist_note_body))
    val essentialNote: String get() = t("essential_note", str(S.desktop_dist_essential_note))
    val mustRead: String get() = t("must_read", str(S.must_read))
    val listingOrderHint: String get() = t("crew_list_header_text", str(S.desktop_cl_header_text))
    val clickHere: String get() = t("click_here_label", str(S.tools_description_click_here))
}

/** Recomposes when a language lands, so nothing on screen keeps its fallback. */
@Composable
fun rememberDistributionCopy(): DistributionCopy {
    val dictionary by Labels.dictionary.collectAsState()
    return remember(dictionary) { DistributionCopy(dictionary) }
}
