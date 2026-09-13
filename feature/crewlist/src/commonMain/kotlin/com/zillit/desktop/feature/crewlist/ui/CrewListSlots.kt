package com.zillit.desktop.feature.crewlist.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.feature.crewlist.ui.dialogs.CrewCanvas
import com.zillit.desktop.feature.crewlist.ui.dialogs.CrewContactActions

/**
 * What the app around the crew list lends its screen: the browser pane for
 * Customise & Preview, the shared Add External User form, faces, and the
 * drawer's call, chat and mail. Every slot may be absent — tests, and the
 * widget, which shows the roster and its PDF only.
 */
class CrewListSlots(
    val canvas: CrewCanvas? = null,
    /** The External Users form; calls back with its success line, or null when cancelled. */
    val externalUserForm: (@Composable (onFinished: (notice: String?) -> Unit) -> Unit)? = null,
    val faces: suspend (String) -> ImageBitmap? = { null },
    val contact: CrewContactActions = CrewContactActions(),
)
