package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.runtime.Composable
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState

/** A screen whose port has not landed: names itself and says so, rather than opening blank. */
@Composable
internal fun NotPorted(title: String) {
    EmptyState(title = title, hint = str(S.desktop_csync_not_ported))
}
