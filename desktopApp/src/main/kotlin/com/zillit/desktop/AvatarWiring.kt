package com.zillit.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.zillit.desktop.core.designsystem.component.CachingAvatarLoader
import com.zillit.desktop.core.designsystem.component.ProvideAvatarLoader
import com.zillit.desktop.core.session.ProjectContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Puts the crew's profile pictures in reach of every avatar in [content].
 *
 * One loader per open production: a switch installs a fresh one, so the
 * previous crew's faces are dropped rather than shown for whoever has the
 * same id on the next production. Before the graph is Ready there is no
 * crew, and every avatar draws initials.
 *
 * Wrapped around each OS window's content — the main frame, torn-off tools,
 * the call window and the widgets — because a composition local does not
 * cross a `Window` boundary.
 */
@Composable
internal fun AvatarFaces(graph: AppGraph, content: @Composable () -> Unit) {
    val ready = graph as? AppGraph.Ready
    val context by (ready?.projectContext?.context ?: EMPTY_CONTEXT).collectAsState()
    val projectId = context?.project?.projectId
    val loader = remember(ready, projectId) {
        ready?.let { CachingAvatarLoader(crewFaceLoader(it)) }
    }
    DisposableEffect(loader) { onDispose { loader?.close() } }
    ProvideAvatarLoader(loader, content)
}

/** Stands in for the context flow while the graph is not Ready. */
private val EMPTY_CONTEXT: StateFlow<ProjectContext?> = MutableStateFlow(null)
