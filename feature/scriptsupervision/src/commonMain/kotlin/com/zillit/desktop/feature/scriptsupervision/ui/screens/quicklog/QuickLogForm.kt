package com.zillit.desktop.feature.scriptsupervision.ui.screens.quicklog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.domain.QuickLog
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormHalf
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.SwitchInput
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.cast
import com.zillit.desktop.feature.scriptsupervision.ui.dropEmpty
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * The quick log's form — the web's `QuickLogForm`.
 *
 * ## One save writes seven records
 *
 * `POST /quick-log/{projectId}` is a single call, and the SERVICE fans it out:
 * it always writes the take, and then, as the fields ask, the facing page, the
 * lined script, a camera detail, a dialogue note, a matching note and a sound
 * report. That is why there is no "which log am I writing" control here — the
 * three issue switches are what decide, and a switch left off means that
 * record is simply not written.
 *
 * ## What is kept and what is cleared
 *
 * After a take: [QuickLog.PER_TAKE] clears and the take number steps. After a
 * new slate: the shot's own fields ([QuickLog.PER_SLATE]) clear too, the slate
 * steps ([QuickLog.nextSlate]) and the take number goes back to 1. The camera
 * settings never clear — they are the point of [QuickLog.CARRIED].
 */
@Composable
internal fun QuickLogForm(scene: Rec, last: LastTake) {
    val ctx = LocalSs.current
    val state = remember(scene.id, last) { QuickLogState(scene, last) }
    var saving by remember(scene.id) { mutableStateOf("") }

    val save = { newSlate: Boolean ->
        if (saving.isBlank()) {
            saving = if (newSlate) "slate" else "take"
            ctx.scope.launch {
                val done = ctx.write {
                    ctx.api.post(
                        "/quick-log/${ctx.api.project().orEmpty()}",
                        // A create, so the fields nobody filled are dropped:
                        // the validator refuses "" for a pick-list field.
                        body(state.payload(newSlate)).dropEmpty(),
                    )
                }
                saving = ""
                if (done != null) state.saved(done.rec?.rec("take"), newSlate)
            }
        }
    }

    TakeHeader(state, scene)
    FlagButtons(state)
    CameraCard(state)
    IssueCards(state)
    SaveRow(
        state = state,
        saving = saving,
        onLogTake = { ctx.whenPost { save(false) } },
        onNewSlate = { ctx.whenPost { save(true) } },
    )
    state.lastSaved?.let { LastSavedLine(it) }
}

/** The slate, the take and the shot — the three things always on screen. */
@Composable
private fun TakeHeader(state: QuickLogState, scene: Rec) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        FormGrid {
            TextInput(
                value = state.slate,
                onChange = state::slateTyped,
                label = t("slate"),
                modifier = FormHalf,
            )
            TextInput(
                value = state.takeNumber,
                onChange = { state.takeNumber = it },
                label = t("take_number"),
                modifier = FormHalf,
                number = true,
                // A scene whose last take could not be read must be told, or
                // a take 1 here would overwrite the numbering already logged.
                help = if (!state.known) t("select_scene_to_start_hint") else null,
            )
            TextInput(
                value = state.duration,
                // Digits only; the colon puts itself in, as typed on set.
                onChange = { state.duration = QuickLog.maskClock(it) },
                label = t("duration"),
                modifier = FormHalf,
                placeholder = "1:52",
            )
            TextInput(
                value = state.shotDescription,
                onChange = { state.shotDescription = it },
                label = t("shot_description"),
                modifier = FormWide,
            )
            TextInput(
                value = state.notes,
                onChange = { state.notes = it },
                label = t("notes"),
                modifier = FormWide,
                multiline = true,
            )
        }
        MutedText(t("scene") + " " + scene.str("scene_number") + " · " + state.takesSoFarText())
    }
}

/**
 * The flags, as big one-tap toggles.
 *
 * Deliberately not a form field: these are hit with one hand while watching a
 * monitor, so each is a target of its own rather than a row in a list.
 */
@Composable
private fun FlagButtons(state: QuickLogState) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        QuickLog.FLAGS.forEach { flag ->
            ZillitChoiceChip(
                label = t("flag_$flag"),
                selected = flag in state.flags,
                onClick = { state.toggleFlag(flag) },
            )
        }
    }
    // Why it was no good: offered only once it IS no good.
    if ("is_ng" in state.flags) {
        FormGrid {
            PickInput(
                value = state.ngReason,
                // The service stores the TEXT, so the values are the phrases.
                options = QuickLog.NG_REASONS.map { t("ng_$it") to t("ng_$it") },
                onChange = { state.ngReason = it },
                label = t("ng_reason"),
                modifier = FormWide,
                clearable = true,
            )
        }
    }
}

/** The camera's settings: what carries from take to take. */
@Composable
private fun CameraCard(state: QuickLogState) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth(), title = t("camera_details")) {
        FormGrid {
            TextInput(state.cameraRoll, { state.cameraRoll = it }, t("cam_roll"), FormHalf)
            TextInput(state.soundRoll, { state.soundRoll = it }, t("sound_roll"), FormHalf)
            PickInput(
                value = state.shotSize,
                options = QuickLog.SHOT_SIZES.map { it to "$it — ${t(QuickLog.shotSizeKey(it))}" },
                onChange = { state.shotSize = it },
                label = t("shot_size"),
                clearable = true,
            )
            PickInput(
                value = state.movement,
                options = QuickLog.MOVEMENTS.map { it to it },
                onChange = { state.movement = it },
                label = t("camera_movement"),
                clearable = true,
            )
            PickInput(
                value = state.angle,
                options = QuickLog.ANGLES.map { it to it },
                onChange = { state.angle = it },
                label = t("camera_angle"),
                clearable = true,
            )
            // Typed as text and stored as numbers; the lists are suggestions,
            // so anything typed is kept.
            TextInput(state.lens, { state.lens = it }, t("lens_mm"), FormHalf, number = true)
            TextInput(state.fps, { state.fps = it }, t("fps"), FormHalf, number = true)
            TextInput(state.shutter, { state.shutter = it }, t("shutter"), FormHalf, number = true)
            TextInput(state.iso, { state.iso = it }, t("exposure_index"), FormHalf, number = true)
            PickInput(
                value = state.colorTemp,
                options = QuickLog.COLOR_TEMPS.map { it to it },
                onChange = { state.colorTemp = it },
                label = t("color_k"),
                modifier = FormHalf,
                clearable = true,
            )
            PickInput(
                value = state.ndFilter,
                options = QuickLog.ND_FILTERS.map { (value, stops) ->
                    value to if (stops == 0) t("nd_clear") else "ND $value ($stops ${t("stop")})"
                },
                onChange = { state.ndFilter = it },
                label = t("nd"),
                clearable = true,
            )
            PickInput(
                value = state.tStop,
                options = QuickLog.T_STOPS.map { it to it },
                onChange = { state.tStop = it },
                label = t("t_stop"),
                modifier = FormHalf,
                clearable = true,
            )
        }
    }
}

/**
 * The three issue blocks: dialogue, continuity, sound.
 *
 * Each switch decides whether the service writes that record at all, so the
 * fields under one appear only when its switch is on — a field filled under an
 * off switch would be typed and then silently dropped.
 */
/**
 * The three issue blocks: dialogue, continuity, sound.
 *
 * Each switch decides whether the service writes that record at all, so the
 * fields under one appear only when its switch is on — a field filled under an
 * off switch would be typed and then silently dropped.
 */
@Composable
private fun IssueCards(state: QuickLogState) {
    DialogueCard(state)
    ContinuityCard(state)
    SoundCard(state)
}

@Composable
private fun DialogueCard(state: QuickLogState) {
    val castState = cast()
    ZillitSectionCard(modifier = Modifier.fillMaxWidth(), title = t("dialogue_notes")) {
        SwitchInput(state.hasDialogue, { state.hasDialogue = it }, t("dialogue_change"), FormWide)
        if (!state.hasDialogue) return@ZillitSectionCard
        FormGrid {
            PickInput(
                value = state.dialogueCharacter,
                options = castState.characters.map { it.str("name") to it.str("name") },
                onChange = { state.dialogueCharacter = it },
                label = t("character"),
                clearable = true,
            )
            PickInput(
                value = state.scriptLine,
                // The scene's own dialogue, headings and cues left out.
                options = QuickLog.scriptLines(state.scene).map { it to it },
                onChange = { state.scriptLine = it },
                label = t("script_line"),
                modifier = FormWide,
                clearable = true,
            )
            TextInput(state.actualLine, { state.actualLine = it }, t("actual_line"), FormWide, multiline = true)
        }
    }
}

@Composable
private fun ContinuityCard(state: QuickLogState) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth(), title = t("matching_notes")) {
        SwitchInput(state.hasContinuity, { state.hasContinuity = it }, t("continuity_issue"), FormWide)
        if (!state.hasContinuity) return@ZillitSectionCard
        FormGrid {
            TextInput(
                state.continuityDescription,
                { state.continuityDescription = it },
                t("describe"),
                FormWide,
                multiline = true,
            )
            PickInput(
                value = state.continuityPriority,
                options = QuickLog.CONTINUITY_PRIORITIES.map { it to t("priority_$it") },
                onChange = { state.continuityPriority = it },
                label = t("priority"),
                clearable = true,
            )
        }
    }
}

@Composable
private fun SoundCard(state: QuickLogState) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth(), title = t("sound_reports")) {
        SwitchInput(state.hasSound, { state.hasSound = it }, t("sound_issue"), FormWide)
        if (!state.hasSound) return@ZillitSectionCard
        FormGrid {
            PickInput(
                value = state.soundQuality,
                options = QuickLog.SOUND_QUALITIES.map { it to t("quality_$it") },
                onChange = { state.soundQuality = it },
                label = t("quality"),
                clearable = true,
            )
            TextInput(state.soundNoise, { state.soundNoise = it }, t("noise_issues"), FormWide, multiline = true)
            SwitchInput(state.soundAdr, { state.soundAdr = it }, t("adr_needed"), FormWide)
        }
    }
}

/**
 * The two ways to save.
 *
 * "Log take" is the one used all day; "New slate" is the one used when the
 * shot changes, and it does more than save — it steps the slate and resets the
 * shot's own fields.
 */
@Composable
private fun SaveRow(state: QuickLogState, saving: String, onLogTake: () -> Unit, onNewSlate: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = t("save_next_take"),
            onClick = onLogTake,
            leadingIcon = ZillitIcons.Save,
            loading = saving == "take",
            enabled = saving.isBlank() && state.slate.isNotBlank(),
        )
        ZillitButton(
            text = t("save_new_slate"),
            onClick = onNewSlate,
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Add,
            loading = saving == "slate",
            enabled = saving.isBlank() && state.slate.isNotBlank(),
        )
    }
}

/** What the last save wrote, so a reader can see it landed without leaving the form. */
@Composable
private fun LastSavedLine(saved: SavedTake) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MutedText("${t("slate")} ${saved.slate} · ${t("take")} ${saved.take}")
        if (saved.circled) SsTag(t("flag_is_circled"), StatusTone.Done)
        if (saved.ng) SsTag(t("flag_is_ng"), StatusTone.Escalated)
    }
}

/** The slate and take a save wrote, and the two flags worth echoing. */
internal data class SavedTake(val slate: String, val take: String, val circled: Boolean, val ng: Boolean)

/**
 * Everything the form holds, and the rules for what a save does to it.
 *
 * One object rather than thirty `remember`s because the resets are the hard
 * part: "log take" and "new slate" clear DIFFERENT sets of fields, and that is
 * much easier to get right — and to read — in one place.
 */
@Stable
internal class QuickLogState(val scene: Rec, last: LastTake) {

    /** The scene's last take could be read, so the take number can be trusted. */
    val known: Boolean = last.known

    private val carried = QuickLog.carriedFrom(last.take)

    var slate by mutableStateOf(carried["slate_number"].orEmpty())
        private set

    var takeNumber by mutableStateOf(
        // The next take after the last one; blank when the last is unknown,
        // so a reader fills it in rather than overwriting take 1.
        if (last.known) ((last.take?.int("take_number") ?: 0) + 1).toString() else "",
    )

    var takesSoFar by mutableStateOf(last.take?.int("take_number") ?: 0)
        private set

    var duration by mutableStateOf("")
    var notes by mutableStateOf("")
    var directorNotes by mutableStateOf("")
    var ngReason by mutableStateOf("")

    var shotDescription by mutableStateOf("")
    var dialogueCoverage by mutableStateOf("")
    var actionDescription by mutableStateOf("")
    var firstShotTime by mutableStateOf("")

    var cameraRoll by mutableStateOf(carried["camera_roll"].orEmpty())
    var soundRoll by mutableStateOf(carried["sound_roll"].orEmpty())
    var shotSize by mutableStateOf("")
    var movement by mutableStateOf("")
    var angle by mutableStateOf("")
    var lens by mutableStateOf(carried["lens_mm"].orEmpty())
    var fps by mutableStateOf(carried["fps"].orEmpty())
    var shutter by mutableStateOf(carried["shutter_angle"].orEmpty())
    var iso by mutableStateOf(carried["iso_ei"].orEmpty())
    var colorTemp by mutableStateOf(carried["color_temp"].orEmpty())
    var ndFilter by mutableStateOf(carried["nd_filter"].orEmpty())
    var tStop by mutableStateOf(carried["t_stop"].orEmpty())

    var hasDialogue by mutableStateOf(false)
    var dialogueCharacter by mutableStateOf("")
    var scriptLine by mutableStateOf("")
    var actualLine by mutableStateOf("")

    var hasContinuity by mutableStateOf(false)
    var continuityDescription by mutableStateOf("")
    var continuityPriority by mutableStateOf("")

    var hasSound by mutableStateOf(false)
    var soundQuality by mutableStateOf("")
    var soundNoise by mutableStateOf("")
    var soundAdr by mutableStateOf(false)

    var flags by mutableStateOf(emptySet<String>())
        private set

    var lastSaved by mutableStateOf<SavedTake?>(null)
        private set

    fun toggleFlag(flag: String) {
        flags = if (flag in flags) flags - flag else flags + flag
    }

    /** A slate typed by hand; the service is asked for ITS last take elsewhere. */
    fun slateTyped(value: String) {
        slate = value
    }

    @Composable
    fun takesSoFarText(): String =
        if (takesSoFar > 0) "$takesSoFar ${t("takes_logged_label")}" else t("no_takes")

    /**
     * The body of the one save.
     *
     * Every flag goes as its own boolean, true or false, because a flag turned
     * OFF has to be written as false rather than left out — the service reads
     * an absent key as "unchanged" and this is a create of a new take.
     */
    fun payload(newSlate: Boolean): List<Pair<String, Any?>> = buildList {
        add("scene_id" to scene.id)
        add("slate_number" to slate.trim())
        add("take_number" to takeNumber.trim().toIntOrNull())
        // The service's own field is seconds; 0 is "not set".
        QuickLog.toSeconds(duration).takeIf { it > 0 }?.let { add("duration_seconds" to it) }
        add("notes" to notes.trim())
        add("director_notes" to directorNotes.trim())
        add("ng_reason" to ngReason)
        add("shot_description" to shotDescription.trim())
        add("dialogue_coverage" to dialogueCoverage.trim())
        add("action_description" to actionDescription.trim())
        add("first_shot_time" to firstShotTime.trim())
        add("camera_roll" to cameraRoll.trim())
        add("sound_roll" to soundRoll.trim())
        add("shot_size" to shotSize)
        add("movement" to movement)
        add("angle" to angle)
        QuickLog.NUMERIC.forEach { key -> add(key to numberFor(key)) }
        add("color_temp" to colorTemp)
        add("nd_filter" to ndFilter)
        add("t_stop" to tStop)
        add("has_dialogue_change" to hasDialogue)
        add("dialogue_character" to dialogueCharacter)
        add("script_line" to scriptLine)
        add("actual_line" to actualLine.trim())
        add("has_continuity_issue" to hasContinuity)
        add("continuity_description" to continuityDescription.trim())
        add("continuity_priority" to continuityPriority)
        add("has_sound_issue" to hasSound)
        add("sound_quality" to soundQuality)
        add("sound_noise_issues" to soundNoise.trim())
        add("sound_adr_needed" to soundAdr)
        // Every flag, both ways round.
        QuickLog.FLAGS.forEach { flag -> add(flag to (flag in flags)) }
        // The service steps the slate itself when told this is a new one.
        if (newSlate) add("is_new_slate" to true)
    }

    private fun numberFor(key: String): Double? = when (key) {
        "lens_mm" -> lens
        "fps" -> fps
        "shutter_angle" -> shutter
        else -> iso
    }.trim().toDoubleOrNull()

    /**
     * What a finished save leaves behind.
     *
     * The service's own answer wins over what was typed: it may have assigned
     * a different take number (another device logged one first), and the next
     * take has to follow ITS number, not ours.
     */
    fun saved(take: Rec?, newSlate: Boolean) {
        val savedSlate = take?.str("slate_number")?.takeIf { it.isNotBlank() } ?: slate
        val savedTake = take?.int("take_number")?.takeIf { it > 0 }
            ?: takeNumber.trim().toIntOrNull()
            ?: 0
        lastSaved = SavedTake(
            slate = savedSlate,
            take = savedTake.toString(),
            circled = "is_circled" in flags,
            ng = "is_ng" in flags,
        )
        clearPerTake()
        if (newSlate) {
            clearPerSlate()
            // A slate we can step, steps; one numbered another way is left
            // for the reader to type, as the web leaves it.
            slate = QuickLog.nextSlate(savedSlate).orEmpty()
            takeNumber = "1"
            takesSoFar = 0
        } else {
            takeNumber = (savedTake + 1).toString()
            takesSoFar += 1
        }
    }

    private fun clearPerTake() {
        duration = ""
        notes = ""
        directorNotes = ""
        ngReason = ""
        flags = emptySet()
        hasDialogue = false
        dialogueCharacter = ""
        scriptLine = ""
        actualLine = ""
        hasContinuity = false
        continuityDescription = ""
        continuityPriority = ""
        hasSound = false
        soundQuality = ""
        soundNoise = ""
        soundAdr = false
    }

    private fun clearPerSlate() {
        shotDescription = ""
        dialogueCoverage = ""
        actionDescription = ""
        firstShotTime = ""
    }
}
