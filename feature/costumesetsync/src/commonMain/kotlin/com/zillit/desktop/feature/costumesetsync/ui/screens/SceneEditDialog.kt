package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.zillit.desktop.feature.costumesetsync.domain.INT_EXT_FALLBACK
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.dateKey
import com.zillit.desktop.feature.costumesetsync.domain.dateMs
import com.zillit.desktop.feature.costumesetsync.ui.DateInput
import com.zillit.desktop.feature.costumesetsync.ui.EnumInput
import com.zillit.desktop.feature.costumesetsync.ui.FormCell
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.PickInput
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The scene page's "Edit scene" form: the fields as free text (the inline editor splits the story day; this one does not). */
private data class SceneForm(
    val name: String,
    val location: String,
    val intExt: String,
    val timeOfDay: String,
    val scriptDay: String,
    val pages: String,
    val shootDate: String,
    val status: String,
    val synopsis: String,
    val episode: String,
)

private fun formOf(s: Rec) = SceneForm(
    s.str("name"), s.str("location"), s.str("int_ext"), s.str("time_of_day"), s.str("script_day"), s.str("pages"),
    dateKey(s.long("shoot_date")), s.str("status"), s.str("synopsis"), s.str("episode"),
)

@Composable
internal fun SceneEditDialog(open: Boolean, scene: Rec, episodes: Boolean, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalSync.current
    var form by remember(open, scene) { mutableStateOf(formOf(scene)) }
    var saving by remember(open) { mutableStateOf(false) }
    FormDialog(
        open = open,
        title = t("csync_edit_scene"),
        onDismiss = onClose,
        confirmLabel = t("csync_save"),
        busy = saving,
        width = 720.dp,
        onConfirm = {
            saving = true
            // Every key is sent, blanks included: an emptied field clears it.
            val body = buildJsonObject {
                put("name", JsonPrimitive(form.name))
                put("location", JsonPrimitive(form.location))
                put("int_ext", JsonPrimitive(form.intExt))
                put("time_of_day", JsonPrimitive(form.timeOfDay))
                put("script_day", JsonPrimitive(form.scriptDay))
                put("pages", JsonPrimitive(form.pages))
                put("status", JsonPrimitive(form.status))
                put("synopsis", JsonPrimitive(form.synopsis))
                put("episode", JsonPrimitive(form.episode))
                put("shoot_date", JsonPrimitive(dateMs(form.shootDate)))
            }
            ctx.launchWrite({ ctx.api.patch("/scenes/${scene.id}", body) }) {
                saving = false
                onClose()
                onSaved()
            }
        },
    ) {
        FormGrid {
            // A scene keeps its number: it is what the script, schedule and call sheets match on.
            TextInput(scene.str("number"), {}, t("csync_field_scene_number"), FormCell, enabled = false, help = t("csync_scene_number_locked"))
            if (episodes) TextInput(form.episode, { form = form.copy(episode = it) }, t("csync_field_episode"), FormCell)
            TextInput(form.name, { form = form.copy(name = it) }, t("csync_field_name"), FormCell)
            TextInput(form.location, { form = form.copy(location = it) }, t("csync_field_location"), FormCell)
            TextInput(form.scriptDay, { form = form.copy(scriptDay = it) }, t("csync_field_script_day"), FormCell)
            PickInput(
                form.intExt, listOf("" to "—") + (ctx.metaList("int_ext").ifEmpty { INT_EXT_FALLBACK }).map { it to it },
                { form = form.copy(intExt = it) }, t("csync_field_int_ext"), FormCell,
            )
            EnumInput(form.timeOfDay, ctx.metaList("times_of_day"), { form = form.copy(timeOfDay = it) }, t("csync_field_time_of_day"), FormCell)
            DateInput(form.shootDate, { form = form.copy(shootDate = it) }, t("csync_field_shoot_date"), FormCell)
            EnumInput(form.status, ctx.metaList("scene_statuses"), { form = form.copy(status = it) }, t("csync_field_status"), FormCell)
            TextInput(form.pages, { form = form.copy(pages = it) }, t("csync_field_pages"), FormCell)
            TextInput(form.synopsis, { form = form.copy(synopsis = it) }, t("csync_field_synopsis"), FormWide, multiline = true)
        }
    }
}
