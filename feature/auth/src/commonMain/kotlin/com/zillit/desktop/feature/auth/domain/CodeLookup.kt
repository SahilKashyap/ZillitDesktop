package com.zillit.desktop.feature.auth.domain

/**
 * What a production code turned out to be.
 *
 * The same box takes both kinds, because the person typing has no way to know
 * which they were given — a coordinator sends "here is the code" either way.
 * The server decides, and the flow branches on its answer rather than on
 * anything the user had to understand.
 */
sealed interface CodeLookup {
    /**
     * A production's own code. Anyone with it may ask to join, so the details
     * step comes next: the production still has to be told who this is.
     */
    data class NeedsDetails(val project: Project) : CodeLookup

    /**
     * A code issued to one person, who has already been added to the
     * production. There is nothing to fill in — they are on it, and the
     * production only has to appear in their list.
     */
    data class AlreadyOn(val projectId: String, val userId: String) : CodeLookup
}
