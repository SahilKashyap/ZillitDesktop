package com.zillit.desktop.feature.scriptsupervision.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.ScriptSupervision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The project's scenes, loaded once for the whole tool, and the shooting day
 * picked in the side bar — the web's `ProjectScenesProvider`.
 *
 * Every screen in this module needs the scene list: it is the one thing a
 * record is filed under, and the scene picker is in every form. Loading it per
 * screen would be a dozen copies of the same fetch, so the shell owns it and
 * a screen that CHANGES scenes calls [reload].
 *
 * The picked day (null = all days) is a per-viewer convenience, remembered per
 * production as the web remembers it — see [ScenesState.day].
 */
@Stable
class ScenesState(private val scope: CoroutineScope, private val ctx: SsCtx) {

    var scenes: List<Rec> by mutableStateOf(emptyList())
        private set

    var loading: Boolean by mutableStateOf(true)
        private set

    /** The shooting day the side bar is filtering by; null is every day. */
    var day: Int? by mutableStateOf(null)

    /** The days that have scenes, in order — what the day picker offers. */
    val days: List<Int>
        get() = scenes.map { it.int("shooting_day") }.filter { it > 0 }.distinct().sorted()

    /** The picked day's scenes, or all of them when no day is picked. */
    val dayScenes: List<Rec>
        get() = day?.let { picked -> scenes.filter { it.int("shooting_day") == picked } } ?: scenes

    /** Scene by id, for a table's Scene column. */
    fun scene(id: String): Rec? = scenes.firstOrNull { it.id == id }

    /** Every scene as picker options: id to "14 · INT. KITCHEN". */
    val options: List<Pair<String, String>>
        get() = scenes.map { it.id to ScriptSupervision.sceneLabel(it) }

    fun reload() {
        loading = true
        scope.launch {
            val project = ctx.api.project()
            val answer = project?.let { ctx.api.get("/scenes/$it", mapOf("limit" to SCENE_PAGE)) }
            if (answer is ZillitResult.Success) {
                scenes = answer.data.rows
            } else if (answer is ZillitResult.Failure) {
                // The shell says nothing: every screen shows its own failure,
                // and a toast on a background list would fire on every launch
                // of a production whose tool is not set up yet.
                scenes = emptyList()
            }
            loading = false
        }
    }

    private companion object {
        /** The web asks for 1000 and treats the answer as the whole list. */
        const val SCENE_PAGE = 1000
    }
}

val LocalScenes = compositionLocalOf<ScenesState> { error("No ScenesState") }

/** `LocalScenes.current`, for a screen that reads the list in one line. */
@Composable
fun scenes(): ScenesState = LocalScenes.current
