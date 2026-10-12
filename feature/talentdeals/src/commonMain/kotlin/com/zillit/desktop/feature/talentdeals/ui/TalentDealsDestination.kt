package com.zillit.desktop.feature.talentdeals.ui

import com.zillit.desktop.feature.talentdeals.domain.TalentDealsViewer

/**
 * The four faces of the tool — the web's four routes under `/film-tools/talent-deals`
 * (`TalentDealsModule.jsx`): the register, one contract read-only, Add Contract
 * (`/new`) and the same form prefilled (`/:id/edit`).
 *
 * [visibleTo] is the single rights gate. Viewing needs `view_access` (or
 * posting, or the accounts department); Add and Edit need posting. A viewer
 * with no posting right who lands on a posting-only face is sent back to the
 * register.
 */
enum class TalentDealsDestination(val requiresPost: Boolean) {
    Register(requiresPost = false),
    View(requiresPost = false),
    New(requiresPost = true),
    Edit(requiresPost = true),
    ;

    fun visibleTo(viewer: TalentDealsViewer): Boolean = viewer.canView && (!requiresPost || viewer.canPost)
}

/**
 * Where the tool is. What the web carries in router state — Add Contract's
 * role — rides on the route here, never in a URL: a refresh or a pasted link
 * on `/new` has no role and goes back to the register, where the picker asks
 * again.
 */
sealed interface TalentRoute {
    val destination: TalentDealsDestination

    data object Register : TalentRoute {
        override val destination get() = TalentDealsDestination.Register
    }

    data class View(val id: String) : TalentRoute {
        override val destination get() = TalentDealsDestination.View
    }

    data class New(val role: String) : TalentRoute {
        override val destination get() = TalentDealsDestination.New
    }

    data class Edit(val id: String) : TalentRoute {
        override val destination get() = TalentDealsDestination.Edit
    }
}
