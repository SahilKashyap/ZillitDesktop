package com.zillit.desktop.feature.scriptsupervision.ui.screens.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.Templates
import com.zillit.desktop.feature.scriptsupervision.domain.asRec
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.Resource
import com.zillit.desktop.feature.scriptsupervision.ui.RowActions
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The template designer's list — the web's `TemplatesScreen`.
 *
 * Every template a production has designed, with what it is made of and what
 * to do with it: use it to log, make it the default, edit, duplicate, export
 * or delete.
 *
 * ## The default is exclusive, and clearing it comes FIRST
 *
 * ⚠ A production has ONE default template, and the service does not enforce
 * it — so setting a new one clears the old one first and gives up if that
 * write fails. Doing it the other way round leaves two defaults, and the
 * quick log then hands over to whichever the list happens to answer first.
 *
 * ## The other two tabs
 *
 * Call sheets and history are empty here, as they are on the web: the
 * standalone's tabs have no data behind them yet either. They are present
 * rather than hidden so the page matches, and so the next session does not
 * read their absence as something it needs to build.
 */
@Composable
internal fun TemplatesScreen(onEdit: (String) -> Unit, onUse: (String) -> Unit) {
    var tab by remember { mutableStateOf("draft") }
    val rows = rememberRows { ContinuityCrud(api, "templates").list() }

    SsPageHeader(title = t("template_designer"), sub = t("template_designer_subtitle"))
    ZillitTabStrip(
        tabs = listOf(
            ZillitTab("draft", t("draft")),
            ZillitTab("callsheet", t("callsheet")),
            ZillitTab("history", t("history")),
        ),
        activeId = tab,
        onSelect = { tab = it },
        modifier = Modifier.fillMaxWidth(),
    )
    when (tab) {
        "draft" -> DraftTab(rows, onEdit, onUse)
        "callsheet" -> EmptyTab(t("no_callsheets"), t("no_callsheets_hint"))
        else -> EmptyTab(t("no_history"), t("no_history_hint"))
    }
}

/** A tab the standalone has not filled in either. */
@Composable
private fun EmptyTab(title: String, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(title, style = ZillitTheme.typography.titleSmall)
        MutedText(hint)
    }
}

/** The templates themselves: search, tag filter, and the table. */
@Composable
private fun DraftTab(rows: Resource<List<Rec>>, onEdit: (String) -> Unit, onUse: (String) -> Unit) {
    val ctx = LocalSs.current
    var search by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deleting by remember { mutableStateOf<Rec?>(null) }
    var analyticsOf by remember { mutableStateOf<Rec?>(null) }

    // `t` is a composable, so the words the writes below need are resolved
    // here rather than inside a coroutine that cannot call it.
    val invalidFile = t("template_file_invalid")
    val copySuffix = t("copy")

    Toolbar(
        search = search,
        onSearch = { search = it },
        onImport = { ctx.importTemplate(invalidFile) { rows.reload() } },
        onNew = { ctx.whenPost { onEdit("") } },
    )

    Await(rows) { all ->
        TagFilter(all, tags, onToggle = { tags = it })
        val shown = all.filter { template ->
            (search.isBlank() || template.str("name").contains(search, ignoreCase = true)) &&
                (tags.isEmpty() || template.strings("tags").any { it in tags })
        }
        ZillitDataTable(
            rows = shown,
            columns = templateColumns(
                onEdit = onEdit,
                onUse = onUse,
                onDefault = { ctx.setDefault(all, it) { rows.reload() } },
                onDuplicate = { ctx.duplicate(it, copySuffix) { rows.reload() } },
                onExport = { ctx.exportTemplate(it) },
                onAnalytics = { analyticsOf = it },
                onDelete = { deleting = it },
            ),
            key = { it.id },
            emptyTitle = t(
                if (search.isNotBlank() || tags.isNotEmpty()) "no_matching_templates" else "no_templates_hint",
            ),
            virtualised = false,
        )
    }

    deleting?.let { template ->
        DeleteDialog(
            title = t("delete_template_title"),
            name = template.str("name"),
            onClose = { deleting = null },
            onDone = { rows.reload(silent = true) },
            run = { ContinuityCrud(ctx.api, "templates").remove(template.id) },
        )
    }
    analyticsOf?.let { TemplateAnalyticsDialog(it) { analyticsOf = null } }
}

/** Search, import and new. */
@Composable
private fun Toolbar(search: String, onSearch: (String) -> Unit, onImport: () -> Unit, onNew: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = search,
            onValueChange = onSearch,
            placeholder = t("search_templates"),
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        ZillitButton(
            text = t("import_template"),
            onClick = onImport,
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Upload,
        )
        ZillitButton(text = t("new_template"), onClick = onNew, leadingIcon = ZillitIcons.Add)
    }
}

/** Every tag any template carries, as a filter. */
@Composable
private fun TagFilter(all: List<Rec>, picked: Set<String>, onToggle: (Set<String>) -> Unit) {
    val tags = all.flatMap { it.strings("tags") }.distinct().sorted()
    if (tags.isEmpty()) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tags.forEach { tag ->
            // ⚠ Secondary, not Tertiary, when unpicked: a tertiary button
            // draws as plain text, and the render showed the filter as a
            // bare word nobody would know to press.
            ZillitButton(
                text = tag,
                onClick = { onToggle(if (tag in picked) picked - tag else picked + tag) },
                variant = if (tag in picked) ButtonVariant.Primary else ButtonVariant.Secondary,
            )
        }
        if (picked.isNotEmpty()) {
            ZillitButton(text = t("clear"), onClick = { onToggle(emptySet()) }, variant = ButtonVariant.Tertiary)
        }
    }
}

/** The table's columns, in the web's own order. */
@Composable
@Suppress("LongParameterList") // One lambda per row action, which is the row's whole job.
private fun templateColumns(
    onEdit: (String) -> Unit,
    onUse: (String) -> Unit,
    onDefault: (Rec) -> Unit,
    onDuplicate: (Rec) -> Unit,
    onExport: (Rec) -> Unit,
    onAnalytics: (Rec) -> Unit,
    onDelete: (Rec) -> Unit,
): List<TableColumn<Rec>> = listOf(
    TableColumn(t("template")) { template ->
        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(template.str("name"), style = ZillitTheme.typography.titleSmall, maxLines = 1)
                if (template.bool("is_default")) SsTag(t("default"), StatusTone.Pending)
            }
            template.str("description").takeIf { it.isNotBlank() }?.let { MutedText(it) }
        }
    },
    TableColumn(t("tags"), ColumnWidth.Fixed(TAGS_COL)) { template ->
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            template.strings("tags").take(TAGS_SHOWN).forEach { SsTag(it) }
        }
    },
    TableColumn(t("sections")) { template ->
        // The first six, as the web shows, then a count — a template of
        // eleven sections would otherwise be a wall of chips.
        val enabled = Templates.loadSections(template).filter { it.enabled }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            enabled.take(SECTIONS_SHOWN).forEach { SsTag(it.label) }
            if (enabled.size > SECTIONS_SHOWN) {
                MutedText(t("more_count", "count" to (enabled.size - SECTIONS_SHOWN)))
            }
        }
    },
    textColumn(t("fields"), ColumnWidth.Fixed(COUNT_COL)) { template ->
        Templates.loadSections(template).filter { it.enabled }.sumOf { it.fields.size }.toString()
    },
    textColumn(t("version"), ColumnWidth.Fixed(COUNT_COL)) { "v${it.int("version").coerceAtLeast(1)}" },
    TableColumn(header = "", width = ColumnWidth.Fixed(USE_COL)) { template ->
        UseCell(template, onDefault = onDefault, onUse = onUse)
    },
    TableColumn(header = "", width = ColumnWidth.Fixed(ACTIONS_COL)) { template ->
        ActionsCell(
            template = template,
            onEdit = onEdit,
            onDuplicate = onDuplicate,
            onExport = onExport,
            onAnalytics = onAnalytics,
            onDelete = onDelete,
        )
    },
)

/** Making a template the default, and logging through it. */
@Composable
private fun UseCell(template: Rec, onDefault: (Rec) -> Unit, onUse: (String) -> Unit) {
    val isDefault = template.bool("is_default")
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitButton(
            text = t(if (isDefault) "default" else "set_default"),
            onClick = { onDefault(template) },
            variant = if (isDefault) ButtonVariant.Primary else ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.StarFilled,
        )
        ZillitButton(text = t("use_template"), onClick = { onUse(template.id) })
    }
}

/**
 * A row's five actions.
 *
 * The web keeps duplicate, export and analytics behind an overflow menu;
 * `RowActions` carries edit and delete only, so they sit beside it as icons
 * rather than growing a menu the module has nowhere else.
 */
@Composable
@Suppress("LongParameterList") // One lambda per action, which is the cell's job.
private fun ActionsCell(
    template: Rec,
    onEdit: (String) -> Unit,
    onDuplicate: (Rec) -> Unit,
    onExport: (Rec) -> Unit,
    onAnalytics: (Rec) -> Unit,
    onDelete: (Rec) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitIconButton(
            icon = ZillitIcons.Copy,
            contentDescription = t("duplicate"),
            onClick = { onDuplicate(template) },
        )
        ZillitIconButton(
            icon = ZillitIcons.Download,
            contentDescription = t("export_json"),
            onClick = { onExport(template) },
        )
        ZillitIconButton(
            icon = ZillitIcons.BarChart,
            contentDescription = t("template_analytics"),
            onClick = { onAnalytics(template) },
        )
        RowActions(onEdit = { onEdit(template.id) }, onDelete = { onDelete(template) })
    }
}

/**
 * Makes [template] the production's default.
 *
 * ⚠ The old default is cleared FIRST and a failure there stops the whole
 * thing. The service does not enforce one default per production, so a
 * half-done swap leaves two — and the quick log then logs through whichever
 * the list answers first.
 */
private fun SsCtx.setDefault(all: List<Rec>, template: Rec, onDone: () -> Unit) {
    scope.launch {
        val crud = ContinuityCrud(api, "templates")
        if (!template.bool("is_default")) {
            val current = all.firstOrNull { it.bool("is_default") && it.id != template.id }
            if (current != null) {
                val cleared = write { crud.update(current.id, body("is_default" to false)) }
                if (cleared == null) return@launch
            }
        }
        write { crud.update(template.id, body("is_default" to !template.bool("is_default"))) }
        onDone()
    }
}

/** Copies a template's DESIGN under a new name. */
private fun SsCtx.duplicate(template: Rec, copySuffix: String, onDone: () -> Unit) {
    scope.launch {
        val copy = buildJsonObject {
            Templates.portable(template).forEach { (key, value) -> put(key, value) }
            put("name", "${template.str("name")} ($copySuffix)")
        }
        if (write { ContinuityCrud(api, "templates").create(copy) } != null) onDone()
    }
}

/** Writes the template's design to a file the viewer chooses. */
private fun SsCtx.exportTemplate(template: Rec) {
    scope.launch {
        val text = PRETTY.encodeToString(JsonObject.serializer(), Templates.portable(template))
        host.save("${Templates.fileSafe(template.str("name"))}_template.json", text.encodeToByteArray())
    }
}

/**
 * Reads a template design from a file and saves it as a new template.
 *
 * ⚠ What comes off the disk is checked before it is sent: a file that is not
 * a template at all is refused here rather than posted for the service to
 * reject, and only the PORTABLE fields travel — see [Templates.portable] for
 * why an import must not carry an id, a version or another project's default.
 */
private fun SsCtx.importTemplate(invalidFileMessage: String, onDone: () -> Unit) {
    scope.launch {
        val picked = host.pick(setOf("json")) ?: return@launch
        val decoded = runCatching {
            Json.parseToJsonElement(picked.bytes.decodeToString()).asRec()
        }.getOrNull()
        if (!Templates.isTemplateFile(decoded)) {
            toast(invalidFileMessage, false)
            return@launch
        }
        if (write { ContinuityCrud(api, "templates").create(Templates.portable(decoded!!)) } != null) onDone()
    }
}

private val SEARCH_WIDTH = 220.dp
private val TAGS_COL = 200.dp
private val COUNT_COL = 88.dp
private val USE_COL = 260.dp
private val ACTIONS_COL = 180.dp
private const val TAGS_SHOWN = 3
private const val SECTIONS_SHOWN = 6
private val PRETTY = Json { prettyPrint = true }
