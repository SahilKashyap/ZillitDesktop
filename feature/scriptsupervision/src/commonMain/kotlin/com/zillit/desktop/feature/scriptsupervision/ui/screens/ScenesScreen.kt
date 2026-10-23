package com.zillit.desktop.feature.scriptsupervision.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.asRec
import com.zillit.desktop.feature.scriptsupervision.domain.ScriptSupervision
import com.zillit.desktop.feature.scriptsupervision.domain.sceneStatusTone
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormHalf
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.RowActions
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.SsPager
import com.zillit.desktop.feature.scriptsupervision.ui.rememberResource
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * The production's scenes — the web's `ScenesScreen`.
 *
 * Searched, narrowed by status, and narrowed again by the shooting day the
 * side bar has picked (which is why the day is a parameter of the fetch, not a
 * filter over what came back: the list is paged on the service).
 *
 * A write here changes both the list AND every other screen's scene picker,
 * so it reloads the shell's own scene list too.
 */
@Suppress("LongMethod") // Header, two filters, the table, the pager and three dialogs.
@Composable
internal fun ScenesScreen() {
    val ctx = LocalSs.current
    val sceneState = scenes()
    var search by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Rec?>(null) }
    val project = ctx.api.project()
    // Zero-based on the wire, as the service takes it (the web sends `page - 1`).
    var page by remember { mutableStateOf(0) }
    // A narrower filter can leave the reader on a page that no longer exists.
    LaunchedEffect(search, status, sceneState.day) { page = 0 }
    val rows = rememberResource(search, status, sceneState.day, project, page) {
        api.get(
            "/scenes/${project.orEmpty()}",
            mapOf(
                "page" to page,
                "limit" to PAGE_SIZE,
                "search" to search.trim(),
                "status" to status,
                "shooting_day" to sceneState.day,
            ),
        ).let { answer ->
            when (answer) {
                is ZillitResult.Failure -> answer
                // The total rides beside the rows: without it the pager cannot
                // know whether there is another page.
                is ZillitResult.Success -> ZillitResult.Success(
                    answer.data.rows to (answer.data.data.asRec()?.long("total") ?: 0L),
                )
            }
        }
    }
    val changed = {
        rows.reload(silent = true)
        sceneState.reload()
    }

    SsPageHeader(
        title = t("scenes"),
        actions = {
            ZillitButton(
                text = t("add_scene"),
                onClick = { ctx.whenPost { creating = true } },
                leadingIcon = ZillitIcons.Add,
            )
        },
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.width(SEARCH_WIDTH),
            placeholder = t("search_scenes"),
        )
        val statuses = ScriptSupervision.SCENE_STATUSES.map { it to t("status_$it") }
        ZillitSearchSelect(
            value = statuses.firstOrNull { it.first == status },
            options = statuses,
            onSelect = { status = it.first },
            label = { it.second },
            placeholder = t("status"),
            onClear = { status = "" }.takeIf { status.isNotBlank() },
            modifier = Modifier.width(STATUS_WIDTH),
        )
    }
    Await(rows) { (records, total) ->
        ZillitDataTable(
            rows = records,
            columns = sceneColumns(onEdit = { editing = it }, onDelete = { deleting = it }),
            key = { it.id },
            emptyTitle = t("no_scenes"),
            virtualised = false,
        )
        SsPager(page = page, pageSize = PAGE_SIZE, total = total.toInt(), onPage = { page = it })
    }

    if (creating || editing != null) {
        SceneForm(editing, onClose = { creating = false; editing = null }, onSaved = changed)
    }
    deleting?.let { scene ->
        DeleteSceneDialog(scene, onClose = { deleting = null }, onDeleted = changed)
    }
}

@Composable
private fun sceneColumns(onEdit: (Rec) -> Unit, onDelete: (Rec) -> Unit): List<TableColumn<Rec>> = listOf(
    textColumn(t("scene"), ColumnWidth.Fixed(96.dp)) { it.str("scene_number") },
    textColumn(t("scene_name")) { it.str("scene_name") },
    textColumn(t("int_ext"), ColumnWidth.Fixed(96.dp)) { it.str("int_ext") },
    textColumn(t("day_night"), ColumnWidth.Fixed(120.dp)) { it.str("day_night") },
    textColumn(t("shooting_day"), ColumnWidth.Fixed(120.dp), numeric = true) {
        it.long("shooting_day").takeIf { day -> day > 0 }?.toString().orEmpty()
    },
    textColumn(t("location")) { it.str("location") },
    TableColumn(t("status"), ColumnWidth.Fixed(140.dp)) { scene ->
        val status = scene.str("status")
        SsTag(label = t("status_$status"), tone = sceneStatusTone(status))
    },
    TableColumn(header = "", width = ColumnWidth.Fixed(64.dp)) { scene ->
        RowActions(onEdit = { onEdit(scene) }, onDelete = { onDelete(scene) })
    },
)

@Suppress("LongMethod") // A scene's eight fields, one after another.
@Composable
private fun SceneForm(scene: Rec?, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalSs.current
    var number by remember(scene) { mutableStateOf(scene?.str("scene_number").orEmpty()) }
    var name by remember(scene) { mutableStateOf(scene?.str("scene_name").orEmpty()) }
    var intExt by remember(scene) { mutableStateOf(scene?.str("int_ext").orEmpty()) }
    var dayNight by remember(scene) { mutableStateOf(scene?.str("day_night").orEmpty()) }
    var shootingDay by remember(scene) {
        mutableStateOf(scene?.long("shooting_day")?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var location by remember(scene) { mutableStateOf(scene?.str("location").orEmpty()) }
    // A new scene starts pending, as the web's form does.
    var status by remember(scene) { mutableStateOf(scene?.str("status").orEmpty().ifBlank { "pending" }) }
    var description by remember(scene) { mutableStateOf(scene?.str("scene_description").orEmpty()) }
    var saving by remember { mutableStateOf(false) }

    FormDialog(
        open = true,
        title = t(if (scene == null) "add_scene" else "edit_scene"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val payload = body(
                    "scene_number" to number.trim(),
                    "scene_name" to name.trim(),
                    // '' rather than left out: the service stores "" for "not
                    // set", and omitting the key would keep the old value.
                    "int_ext" to intExt,
                    "day_night" to dayNight,
                    "shooting_day" to (shootingDay.trim().toLongOrNull() ?: 0L),
                    "location" to location.trim(),
                    "status" to status,
                    "scene_description" to description,
                )
                val project = ctx.api.project().orEmpty()
                val done = ctx.write {
                    if (scene == null) {
                        ctx.api.post("/scenes/$project", payload)
                    } else {
                        ctx.api.put("/scenes/${scene.id}", payload)
                    }
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = number.isNotBlank(),
        busy = saving,
    ) {
        FormGrid {
            TextInput(number, { number = it }, t("scene"), FormHalf)
            TextInput(name, { name = it }, t("scene_name"))
            PickInput(
                value = intExt,
                options = ScriptSupervision.INT_EXT.map { it to it },
                onChange = { intExt = it },
                label = t("int_ext"),
                clearable = true,
            )
            PickInput(
                value = dayNight,
                options = ScriptSupervision.DAY_NIGHT.map { it to it },
                onChange = { dayNight = it },
                label = t("day_night"),
                clearable = true,
            )
            TextInput(shootingDay, { shootingDay = it }, t("shooting_day"), FormHalf, number = true)
            TextInput(location, { location = it }, t("location"))
            PickInput(
                value = status,
                options = ScriptSupervision.SCENE_STATUSES.map { it to t("status_$it") },
                onChange = { status = it },
                label = t("status"),
            )
            TextInput(description, { description = it }, t("description"), FormWide, multiline = true)
        }
    }
}

@Composable
private fun DeleteSceneDialog(scene: Rec, onClose: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalSs.current
    var busy by remember { mutableStateOf(false) }
    FormDialog(
        open = true,
        title = t("delete_scene_title"),
        onDismiss = onClose,
        confirmLabel = t("delete"),
        onConfirm = {
            busy = true
            ctx.scope.launch {
                val done = ctx.write { ctx.api.delete("/scenes/${scene.id}") }
                busy = false
                if (done != null) {
                    onClose()
                    onDeleted()
                }
            }
        },
        busy = busy,
        danger = true,
        width = 440.dp,
    ) {
        com.zillit.desktop.core.designsystem.component.ZillitText(
            "${t("scene")} ${scene.str("scene_number")}",
        )
    }
}

private const val PAGE_SIZE = 50
private val SEARCH_WIDTH = 260.dp
private val STATUS_WIDTH = 180.dp
