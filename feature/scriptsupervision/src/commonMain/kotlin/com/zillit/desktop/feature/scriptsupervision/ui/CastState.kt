package com.zillit.desktop.feature.scriptsupervision.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The production's characters and story days, loaded once for the whole tool —
 * the web's `useCharacters` and `storyDaysApi` list, which nine screens call
 * on their own.
 *
 * They belong beside [ScenesState] for the same reason the scenes do: a
 * character picker is in the look form, the actor form, the photo form and the
 * breakdown, and the story days decide what day a scene is filed under on
 * three different screens. Loading them per screen would be nine copies of
 * one fetch; a screen that CHANGES either calls [reload].
 */
@Stable
class CastState(private val scope: CoroutineScope, private val ctx: SsCtx) {

    var characters: List<Rec> by mutableStateOf(emptyList())
        private set

    var storyDays: List<Rec> by mutableStateOf(emptyList())
        private set

    var actors: List<Rec> by mutableStateOf(emptyList())
        private set

    var loading: Boolean by mutableStateOf(true)
        private set

    /** A character by id, for a row's Character column. */
    fun character(id: String): Rec? = characters.firstOrNull { it.id == id }

    /** Characters as picker options: id to name. */
    val options: List<Pair<String, String>>
        get() = characters.map { it.id to it.str("name") }

    /** Actors as picker options: id to name. */
    val actorOptions: List<Pair<String, String>>
        get() = actors.map { it.id to it.str("name") }

    /**
     * The actor's name for an id.
     *
     * The service stores it BESIDE the id on the character record, so a card
     * needs no join; the two are written together or the name goes stale.
     */
    fun actorName(id: String): String = actors.firstOrNull { it.id == id }?.str("name").orEmpty()

    /** The character's name for an id, stored beside it for the same reason. */
    fun characterName(id: String): String = character(id)?.str("name").orEmpty()

    /** The story days in narrative order — the order the service's `order` field sets. */
    val daysInOrder: List<Rec>
        get() = storyDays.sortedBy { if (it.has("order")) it.int("order") else Int.MAX_VALUE }

    fun reload() {
        loading = true
        scope.launch {
            characters = rowsOf("characters")
            storyDays = rowsOf("story-days")
            actors = rowsOf("actors")
            loading = false
        }
    }

    /**
     * One list, or what is already held.
     *
     * Silent on failure, as [ScenesState] is: these two run in the background
     * on every launch, and a toast here would fire for a production whose tool
     * is not set up yet.
     */
    private suspend fun rowsOf(folder: String): List<Rec> =
        when (val answer = ContinuityCrud(ctx.api, folder).list()) {
            is ZillitResult.Success -> answer.data.rows
            is ZillitResult.Failure -> emptyList()
        }
}

val LocalCast = compositionLocalOf<CastState> { error("No CastState") }

/** `LocalCast.current`, for a screen that reads the cast in one line. */
@Composable
fun cast(): CastState = LocalCast.current
