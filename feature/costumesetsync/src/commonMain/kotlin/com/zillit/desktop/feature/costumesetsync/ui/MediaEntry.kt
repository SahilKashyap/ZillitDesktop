package com.zillit.desktop.feature.costumesetsync.ui

import com.zillit.desktop.feature.costumesetsync.domain.PickedFile

/** What a form collected for a record that does not exist yet: a picked file, or a link from Add link. */
sealed interface MediaEntry {
    /** [kind] overrides the form's kind for this file (the web's `csyncKind`). */
    class Local(val file: PickedFile, val kind: String? = null) : MediaEntry

    class Link(val url: String, val title: String) : MediaEntry
}
