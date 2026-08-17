package com.zillit.desktop.feature.documentdistribution.ui

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
enum class DocDistDestination(val slug: String, val label: String) {

    /** The date-grouped catalogue of folders and documents. The tool's home. */
    Library("library", "Library"),

    /** What has been sent, and who has opened it. */
    History("history", "History"),

    /** Saved recipient sets — "presets" on the wire. */
    Lists("lists", "Distribution Lists"),

    /** Everyone ever sent to on this production. */
    AddressBook("contacts", "Address Book"),

    /** Reusable subject + body pairs for the composer. */
    Templates("templates", "Templates"),
    ;

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
