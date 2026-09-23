package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.DocDistBadges
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer

/**
 * Every page this tool can show.
 *
 * An enum rather than free-form route strings so the shell cannot navigate
 * somewhere that does not render, and so [visibleTo] is the single place that
 * decides who sees what. The web spreads the same decision across a rights
 * hook, a header's conditional buttons and a route guard, and they have
 * disagreed — a user with view-only rights still got a Compose button that
 * failed on submit.
 *
 * [slug] matches the web's own surfaces so a deep link means the same thing on
 * both clients.
 */
enum class DocDistDestination(val slug: String, private val labelKey: String) {

    /** The date-grouped catalogue of folders and documents. The tool's home. */
    Library("library", S.library),

    /** What has been sent, and who has opened it. */
    History("history", S.history),

    /** Saved recipient sets — "presets" on the wire. */
    Lists("lists", S.dd_lists),

    /** Everyone ever sent to on this production. */
    AddressBook("contacts", S.desktop_address_book),

    /** Reusable subject + body pairs for the composer. */
    Templates("templates", S.templates),
    ;

    val label: String get() = str(labelKey)

    /**
     * The units whose rows a section shows and reads on entry (`Library.jsx:150-153`);
     * empty for the library itself.
     */
    val badgeUnits: List<String>
        get() = when (this) {
            History -> listOf(DocDistBadges.UNIT_PUBLICATION)
            Lists -> listOf(DocDistBadges.UNIT_DISTRIBUTION, DocDistBadges.UNIT_PRESET)
            Templates -> listOf(DocDistBadges.UNIT_TEMPLATE)
            Library, AddressBook -> emptyList()
        }

    /**
     * Whether [viewer] may open this page.
     *
     * Only two rules, because this tool's rights are two flags rather than a
     * hierarchy:
     *
     *  - **View** gates the whole tool, so a viewer without it sees no pages at
     *    all and the screen says so once rather than per tab.
     *  - **History** additionally needs posting rights. It lists what left the
     *    production and to whom, which is a sender's record — showing it to
     *    someone who may only read the library exposes the distribution list of
     *    every document they can see.
     */
    fun visibleTo(viewer: DocDistViewer): Boolean = when {
        !viewer.canView && viewer.ready -> false
        this == History -> viewer.canPost
        else -> true
    }

    companion object {
        fun fromSlug(slug: String?): DocDistDestination? =
            entries.firstOrNull { it.slug == slug }

        /**
         * Where this viewer lands.
         *
         * Always the library when they can see it — that is what the tool is
         * for. Falls through to the first page they can open so an unusual
         * rights combination never lands on a blank screen.
         */
        fun landing(viewer: DocDistViewer): DocDistDestination =
            entries.firstOrNull { it.visibleTo(viewer) } ?: Library
    }
}
