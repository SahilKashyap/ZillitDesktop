package com.zillit.desktop.feature.distribution.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.zillit.desktop.core.localization.LabelDictionary
import com.zillit.desktop.core.localization.Labels

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

    val title: String get() = t("Document_Distribution", "Distribution List")
    val search: String get() = t("Search", "Search")
    val home: String get() = t("Home", "Home")
    val tools: String get() = t("Tools", "Tools")
    val filterUnits: String get() = t("Filter Units or Tools", "Filter Units or Tools")
    val filterExternal: String get() = t("Filter External Users", "Filter External Users")
    val user: String get() = t("User", "User")
    val outsider: String get() = t("Outsider", "Outsider")
    val noDetails: String get() = t("No_details", "No details")
    val note: String get() = t("NOTE", "Note")
    val noteBody: String get() = t("distribution_list_note", NOTE_FALLBACK)
    val essentialNote: String get() = t("essential_note", "Essential Note")
    val mustRead: String get() = t("must_read", "MUST READ")
    val listingOrderHint: String get() = t("crew_list_header_text", LISTING_ORDER_FALLBACK)
    val clickHere: String get() = t("click_here_label", "Click Here")

    private companion object {
        const val NOTE_FALLBACK =
            "ALL USERS ARE LISTED IN THE DISTRIBUTION LIST INCLUDING THE ONES LISTED IN THE MODULE CALLED " +
                "‘EXTERNAL USERS’. SELECTION MUST BE MADE FOR EACH USER, AGAINST EACH MODULE, SO AS TO " +
                "DISTRIBUTE THE DATA WHEN IT IS UPLOADED ON IT. ALSO, PLEASE SEE BELOW."
        const val LISTING_ORDER_FALLBACK =
            "You can rearrange the department listing by navigating to Settings → Admin Settings → " +
                "Listing Order for Crew List"
    }
}

/** Recomposes when a language lands, so nothing on screen keeps its fallback. */
@Composable
fun rememberDistributionCopy(): DistributionCopy {
    val dictionary by Labels.dictionary.collectAsState()
    return remember(dictionary) { DistributionCopy(dictionary) }
}
