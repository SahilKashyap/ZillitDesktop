package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.humanize
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.Clear
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.EnumInput
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.ListRow
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.RowTitle
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val SEARCH_WIDTH = 420
private const val CAST_BOX_WIDTH = 78
private const val CHARACTERS_TAB = "characters"
private const val ACTORS_TAB = "actors"
private const val DEFAULT_TYPE = "SUPPORTING"
private val HalfCell = Modifier.width(290.dp)
private val FullCell = Modifier.width(592.dp)

/** Whether the character matches the list's search (name, actor, type, cast number, description). */
internal fun characterMatches(c: Rec, needle: String): Boolean {
    val n = needle.trim().lowercase()
    if (n.isEmpty()) return true
    return listOf(c.str("name"), c.rec("actor")?.str("name").orEmpty(), tEnum(c.str("type")), c.str("cast_number"), c.str("description"))
        .any { it.isNotEmpty() && it.lowercase().contains(n) }
}

/**
 * "List of Characters" (the web's `CharactersScreen`): two in-page tabs
 * (Characters, Actors), the "★ Actors" button to the full actors table, and
 * **Cast numbers** — every row turns into a number box so a whole cast can be
 * numbered from the list itself. Cast-number order, unnumbered ones after.
 */
@Composable
fun CharactersScreen() {
    val ctx = LocalSync.current
    val characters = rememberResource { api.get("/characters").mapRows() }
    val actors = rememberResource { api.get("/actors").mapRows() }
    var tab by remember { mutableStateOf(CHARACTERS_TAB) }
    var q by remember { mutableStateOf("") }
    var charOpen by remember { mutableStateOf(false) }
    var actorOpen by remember { mutableStateOf(false) }
    var numbering by remember { mutableStateOf(false) }
    var savingNumbers by remember { mutableStateOf(false) }
    val draft = remember { mutableStateMapOf<String, String>() }

    fun reload() {
        characters.reload(silent = true)
        actors.reload(silent = true)
    }
    // A colleague's write must not re-sort the list under someone halfway through numbering it.
    SocketRefresh(SyncEvents.Character + SyncEvents.Actor) { if (!numbering) reload() }

    val isCharacters = tab == CHARACTERS_TAB
    val charRows = characters.value
    val actorRows = actors.value
    Column(verticalArrangement = Arrangement.spacedBy(PAGE_GAP.dp)) {
        PageHead(
            title = t("csync_list_of_characters"),
            sub = t("csync_characters_page_sub"),
            note = t("csync_characters_order_note"),
            modifier = Modifier.padding(top = 12.dp),
            actions = {
                ZillitButton(t("csync_nav_actors"), onClick = { ctx.nav.go("actors") }, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.StarFilled)
                if (ctx.canPost && isCharacters) {
                    if (numbering) {
                        ZillitButton(
                            t("csync_save"),
                            onClick = {
                                ctx.scope.launch {
                                    savingNumbers = true
                                    val saved = saveCastNumbers(ctx, charRows.orEmpty(), draft)
                                    savingNumbers = false
                                    if (saved) {
                                        numbering = false
                                        draft.clear()
                                        reload()
                                    }
                                }
                            },
                            variant = ButtonVariant.Secondary,
                            loading = savingNumbers,
                        )
                    } else {
                        ZillitButton("# ${t("csync_cast_numbers")}", onClick = { numbering = true }, variant = ButtonVariant.Secondary)
                    }
                }
                if (ctx.canPost) {
                    ZillitButton(
                        t(if (isCharacters) "csync_character" else "csync_actor"),
                        onClick = { if (isCharacters) charOpen = true else actorOpen = true },
                        leadingIcon = ZillitIcons.Add,
                    )
                }
            },
        )
        if (charRows == null || actorRows == null) {
            Await(if (charRows == null) characters else actors) { }
        } else {
            InkTabs(
                listOf(
                    CHARACTERS_TAB to "${t("csync_characters_title")} (${charRows.size})",
                    ACTORS_TAB to "${t("csync_actors_title")} (${actorRows.size})",
                ),
                tab,
            ) { tab = it }
            if ((if (isCharacters) charRows else actorRows).isNotEmpty()) {
                SearchWithButton(q, { q = it }, t(if (isCharacters) "csync_characters_search" else "csync_actors_search"), Modifier.fillMaxWidth())
            }
            if (isCharacters && charRows.isNotEmpty() && q.isBlank()) CastOrderNote(charRows, numbering)
            SectionCard(flush = true) {
                if (isCharacters) CharacterRows(charRows, q, numbering, draft) else ActorRows(actorRows, q)
            }
        }
    }
    NewCharacterFull(charOpen, { charOpen = false }, actors.value.orEmpty(), ::reload)
    QuickActorDialog(actorOpen, { actorOpen = false }, ::reload)
}

/** The list scrolls inside its card (the web's `.csync-rows { max-height: 62vh }`). */
private const val LIST_MAX_HEIGHT = 496
private const val PAGE_GAP = 16

@Composable
private fun CastOrderNote(characters: List<Rec>, numbering: Boolean) {
    val unnumbered = characters.count { !it.has("cast_number") }
    val colors = ZillitTheme.colors
    val style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp)
    val text = buildAnnotatedString {
        append(t("csync_in_cast_order"))
        if (unnumbered > 0) {
            append(" · ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(unnumbered.toString()) }
            append(" ${t("csync_of")} ${characters.size} ${t("csync_still_no_cast_number")}")
            if (numbering) append(", ${t("csync_type_it_beside_name")}")
        }
    }
    ZillitText(text, style = style, color = colors.textMuted, maxLines = 2)
}

@Composable
private fun CharacterRows(characters: List<Rec>, q: String, numbering: Boolean, draft: MutableMap<String, String>) {
    val ctx = LocalSync.current
    if (characters.isEmpty()) {
        EmptyState(t("csync_characters_empty_title"))
        return
    }
    // Rows stay where they are while numbering (re-read only once Save lands), so a row never jumps out from under the box being typed in.
    val shown = castOrder(characters).filter { characterMatches(it, q) }
    if (shown.isEmpty()) {
        EmptyState(t("csync_characters_no_match"), t("csync_characters_no_match_hint"))
        return
    }
    Column(Modifier.heightIn(max = LIST_MAX_HEIGHT.dp).verticalScroll(rememberScrollState())) {
        shown.forEachIndexed { index, c ->
            val counts = c.rec("counts")
            val actorName = c.rec("actor")?.str("name").orEmpty().ifEmpty { t("csync_no_actor_assigned") }
            val age = c.str("age").takeIf { it.isNotEmpty() && it != "0" }?.let { " · ${t("csync_age_lower")} $it" }.orEmpty()
            val n = { key: String -> (counts?.long(key) ?: 0L).toString() }
            CharListRow(
                onClick = if (numbering) null else ({ ctx.nav.go("characters/${c.id}") }),
                leading = { if (numbering) CastBox(c, draft) else SquareAvatar(c.str("cast_number")) },
                title = c.str("name"),
                sub = actorName + age,
                end = "${n("scenes")} ${t("csync_count_scenes")} · ${n("changes")} ${t("csync_count_changes")} · ${n("costumes")} ${t("csync_count_pieces")}",
                last = index == shown.lastIndex,
            )
        }
    }
}

@Composable
private fun ActorRows(actors: List<Rec>, q: String) {
    if (actors.isEmpty()) {
        EmptyState(t("csync_actors_empty_title"))
        return
    }
    val needle = q.trim().lowercase()
    val shown = actors.filter { a ->
        needle.isEmpty() ||
            (listOf(a.str("name"), a.str("agency"), a.str("phone"), a.str("email")) + a.recs("characters").map { it.str("name") })
                .any { it.lowercase().contains(needle) }
    }
    if (shown.isEmpty()) {
        EmptyState(t("csync_actors_no_match"), t("csync_actors_no_match_hint"))
        return
    }
    Column(Modifier.heightIn(max = LIST_MAX_HEIGHT.dp).verticalScroll(rememberScrollState())) {
        shown.forEachIndexed { index, a ->
            val phone = a.str("phone").takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
            CharListRow(
                onClick = null,
                leading = { SquareAvatar(nameInitials(a.str("name"))) },
                title = a.str("name"),
                sub = a.recs("characters").joinToString(", ") { it.str("name") }.ifEmpty { t("csync_no_character") } + phone,
                extra = (a.rec("measurements")?.let { m -> m.keys.joinToString(" · ") { "$it ${m.str(it)}" } }).orEmpty(),
                last = index == shown.lastIndex,
            )
        }
    }
}

/** A cast-number box: its value is written as soon as the user leaves it, and Enter leaves it. */
@Composable
private fun CastBox(c: Rec, draft: MutableMap<String, String>) {
    val ctx = LocalSync.current
    val focus = LocalFocusManager.current
    var wasFocused by remember { mutableStateOf(false) }
    TextInput(
        value = draft[c.id] ?: c.str("cast_number"),
        onChange = { draft[c.id] = it },
        label = "",
        modifier = Modifier
            .width(CAST_BOX_WIDTH.dp)
            .onFocusChanged {
                if (wasFocused && !it.hasFocus) ctx.scope.launch { commitCast(ctx, c, draft[c.id]) }
                wasFocused = it.hasFocus
            }
            .onPreviewKeyEvent {
                if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) {
                    focus.clearFocus()
                    true
                } else {
                    false
                }
            },
        number = true,
    )
}

private fun currentCast(c: Rec): Long? = if (c.has("cast_number")) c.long("cast_number") else null

/** Writes one box on blur; only a failure speaks (a quiet success, as the web). */
private suspend fun commitCast(ctx: SyncCtx, c: Rec, typed: String?) {
    if (typed == null) return
    when (val change = typedCastNumber(typed, currentCast(c))) {
        CastChange.Unchanged -> Unit
        CastChange.Invalid -> ctx.toast(t("csync_cast_number_invalid"), false)
        is CastChange.To -> (ctx.api.patch("/characters/${c.id}", body("cast_number" to (change.number ?: Clear))) as? ZillitResult.Failure)
            ?.let { ctx.toast(it.error.localised(), false) }
    }
}

/**
 * Save takes whatever is still in the boxes with it — a number typed and not
 * tabbed out of is still a number somebody typed. All or nothing before any
 * write: an invalid box refuses the save. Returns whether every write landed.
 */
private suspend fun saveCastNumbers(
    ctx: SyncCtx,
    characters: List<Rec>,
    draft: Map<String, String>,
): Boolean {
    val pending = mutableListOf<Pair<String, Long?>>()
    for (c in characters) {
        val typed = draft[c.id] ?: continue
        when (val change = typedCastNumber(typed, currentCast(c))) {
            CastChange.Unchanged -> Unit
            CastChange.Invalid -> {
                ctx.toast(t("csync_cast_number_invalid"), false)
                return false
            }
            is CastChange.To -> pending += c.id to change.number
        }
    }
    var message: String? = null
    for ((id, number) in pending) {
        when (val result = ctx.api.patch("/characters/$id", body("cast_number" to (number ?: Clear)))) {
            is ZillitResult.Failure -> {
                ctx.toast(result.error.localised(), false)
                return false
            }
            is ZillitResult.Success -> message = result.data.message
        }
    }
    message?.takeIf { it.isNotBlank() }?.let { ctx.toast(it.localisedMessage(), true) }
    if (pending.isNotEmpty()) ctx.changed()
    return true
}

/** The characters list's own "+ Character": the full new-character fields, with an actor picker. */
@Composable
private fun NewCharacterFull(open: Boolean, onClose: () -> Unit, actors: List<Rec>, onDone: () -> Unit) {
    val ctx = LocalSync.current
    var name by remember(open) { mutableStateOf("") }
    var type by remember(open) { mutableStateOf(DEFAULT_TYPE) }
    var age by remember(open) { mutableStateOf("") }
    var cast by remember(open) { mutableStateOf("") }
    var actorId by remember(open) { mutableStateOf("") }
    var description by remember(open) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val badCast = castNumberInvalid(cast)
    FormDialog(
        open = open,
        title = t("csync_new_character"),
        onDismiss = onClose,
        confirmLabel = t("csync_create"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val answer = ctx.write {
                    ctx.api.post(
                        "/characters",
                        body(
                            "name" to name.trim(),
                            "type" to type,
                            "age" to age.trim().toLongOrNull(),
                            "cast_number" to castNumberOrNull(cast),
                            "actor_id" to actorId,
                            "description" to description,
                        ),
                    )
                }
                saving = false
                if (answer != null) {
                    onDone()
                    onClose()
                }
            }
        },
        confirmEnabled = name.isNotBlank() && !badCast,
        busy = saving,
    ) {
        FormGrid {
            TextInput(name, { name = it }, t("csync_field_name"), FullCell)
            EnumInput(type, ctx.metaList("character_types"), { type = it }, t("csync_field_type"), HalfCell)
            TextInput(age, { age = it }, t("csync_field_age"), HalfCell, number = true)
            TextInput(cast, { cast = it }, t("csync_field_cast_number"), HalfCell, help = t("csync_field_cast_number_hint"), error = if (badCast) t("csync_cast_number_invalid") else null)
            ActorSelect(actorId, { actorId = it }, t("csync_field_actor"), actors.map { it.id to it.str("name") }, FullCell)
            TextInput(description, { description = it }, t("csync_field_description"), FullCell, multiline = true)
        }
    }
}

/** The Actors tab's "+ Actor": name, contact, agency, the standard measurements and notes. */
@Composable
private fun QuickActorDialog(open: Boolean, onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalSync.current
    var name by remember(open) { mutableStateOf("") }
    var phone by remember(open) { mutableStateOf("") }
    var email by remember(open) { mutableStateOf("") }
    var agency by remember(open) { mutableStateOf("") }
    var notes by remember(open) { mutableStateOf("") }
    val measures = remember(open) { mutableStateMapOf<String, String>() }
    var saving by remember { mutableStateOf(false) }
    FormDialog(
        open = open,
        title = t("csync_new_actor"),
        onDismiss = onClose,
        confirmLabel = t("csync_add"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                // Empty boxes would otherwise be stored as "" against every measurement.
                val measurements = buildJsonObject { measures.filterValues { it.isNotBlank() }.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
                val answer = ctx.write {
                    ctx.api.post(
                        "/actors",
                        body("name" to name.trim(), "phone" to phone, "email" to email, "agency" to agency, "measurements" to measurements, "notes" to notes),
                    )
                }
                saving = false
                if (answer != null) {
                    onDone()
                    onClose()
                }
            }
        },
        confirmEnabled = name.isNotBlank(),
        busy = saving,
    ) {
        FormGrid {
            TextInput(name, { name = it }, t("csync_field_name"), FullCell)
            TextInput(phone, { phone = it }, t("csync_field_phone"), HalfCell)
            TextInput(email, { email = it }, t("csync_field_email"), HalfCell)
            TextInput(agency, { agency = it }, t("csync_field_agency"), HalfCell)
        }
        ZillitText(t("csync_field_measurements"), style = ZillitTheme.typography.titleSmall)
        FormGrid {
            ActorMeasures.forEach { m -> TextInput(measures[m].orEmpty(), { measures[m] = it }, humanize(m), Modifier.width(180.dp)) }
        }
        TextInput(notes, { notes = it }, t("csync_field_notes"), FullCell, multiline = true)
    }
}
