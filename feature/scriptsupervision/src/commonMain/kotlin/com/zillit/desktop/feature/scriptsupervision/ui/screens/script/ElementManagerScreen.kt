package com.zillit.desktop.feature.scriptsupervision.ui.screens.script

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
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.RowActions
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.dropEmpty
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * The breakdown's elements — the web's `ElementManagerScreen`.
 *
 * Every prop, vehicle, costume and effect the script needs, once each, with
 * the number of scenes it appears in. This is the list the breakdown tags
 * scenes WITH, so it is the production's vocabulary: one "steel tiffin" here,
 * tagged into nine scenes, rather than nine spellings of it.
 *
 * ⚠ A production with no categories gets the standard set on first visit —
 * `POST /breakdown/seed-categories` is idempotent and the web calls it
 * silently, without toasting its success. Without categories there is nothing
 * to file an element under, so the screen would be unusable.
 */
@Suppress("LongMethod") // Header, two filters, the table and two dialogs.
@Composable
internal fun ElementManagerScreen() {
    val ctx = LocalSs.current
    val project = ctx.api.project().orEmpty()
    var categories by remember(project) { mutableStateOf<List<Rec>?>(null) }
    var categorySlug by remember(project) { mutableStateOf("") }
    var search by remember(project) { mutableStateOf("") }
    var editing by remember(project) { mutableStateOf<Rec?>(null) }
    var creating by remember(project) { mutableStateOf(false) }
    var deleting by remember(project) { mutableStateOf<Rec?>(null) }

    // The categories first, seeding them if this production has none.
    LaunchedEffect(project) {
        categories = ctx.categoriesOrSeed()
    }
    val rows = rememberRows(project, categorySlug, search) {
        api.get(
            "/breakdown/elements/$project",
            mapOf("category_slug" to categorySlug, "search" to search.trim()),
        )
    }

    SsPageHeader(
        title = t("element_manager"),
        actions = {
            ZillitButton(
                text = t("new_item"),
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
            placeholder = t("filter"),
        )
        val options = categories.orEmpty().map { it.str("slug") to it.str("name") }
        ZillitSearchSelect(
            value = options.firstOrNull { it.first == categorySlug },
            options = options,
            onSelect = { categorySlug = it.first },
            label = { it.second },
            placeholder = t("category"),
            onClear = { categorySlug = "" }.takeIf { categorySlug.isNotBlank() },
            modifier = Modifier.width(FILTER_WIDTH),
        )
    }
    Await(rows) { elements ->
        ZillitDataTable(
            rows = elements,
            columns = elementColumns(
                categories = categories.orEmpty(),
                onEdit = { editing = it },
                onDelete = { deleting = it },
            ),
            key = { it.id },
            emptyTitle = t("no_items_matching"),
            virtualised = false,
        )
    }

    if (creating || editing != null) {
        ElementForm(
            element = editing,
            categories = categories.orEmpty(),
            presetCategory = categorySlug,
            onClose = { creating = false; editing = null },
            onSaved = { rows.reload(silent = true) },
        )
    }
    deleting?.let { element ->
        DeleteDialog(
            title = t("delete_element_title"),
            name = element.str("name"),
            onClose = { deleting = null },
            onDone = { rows.reload(silent = true) },
            run = { ContinuityCrud(ctx.api, "breakdown-elements").remove(element.id) },
        )
    }
}

/**
 * The production's breakdown categories, in their own sort order.
 *
 * A production with none gets the standard set seeded. The seed's success is
 * NOT toasted — it is setup, not something the reader asked for — but its
 * failure is, because without categories nothing on this screen can be filed.
 */
private suspend fun SsCtx.categoriesOrSeed(): List<Rec> {
    val existing = ContinuityCrud(api, "breakdown-categories").list()
    val rows = (existing as? ZillitResult.Success)?.data?.rows.orEmpty()
    if (rows.isNotEmpty()) return rows.sortedBy { it.int("sort_order") }
    val project = api.project() ?: return emptyList()
    val seeded = api.post("/breakdown/seed-categories/$project")
    if (seeded is ZillitResult.Failure) {
        toast(seeded.error.localised(), false)
        return emptyList()
    }
    return (seeded as ZillitResult.Success).data.rows.sortedBy { it.int("sort_order") }
}

@Composable
private fun elementColumns(
    categories: List<Rec>,
    onEdit: (Rec) -> Unit,
    onDelete: (Rec) -> Unit,
): List<TableColumn<Rec>> = listOf(
    textColumn(t("category"), ColumnWidth.Fixed(CATEGORY_COL)) { element ->
        // The record keeps the slug; the name lives on the category.
        categories.firstOrNull { it.str("slug") == element.str("category_slug") }?.str("name")
            ?: element.str("category_slug")
    },
    textColumn(t("name")) { it.str("name") },
    textColumn(t("scenes"), ColumnWidth.Fixed(SCENES_COL), numeric = true) {
        it.int("scene_count").takeIf { count -> count > 0 }?.toString().orEmpty()
    },
    textColumn(t("description")) { it.str("description") },
    TableColumn(header = "", width = ColumnWidth.Fixed(ACTIONS_COL)) { element ->
        RowActions(onEdit = { onEdit(element) }, onDelete = { onDelete(element) })
    },
)

@Composable
private fun ElementForm(
    element: Rec?,
    categories: List<Rec>,
    presetCategory: String,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalSs.current
    var slug by remember(element) {
        mutableStateOf(element?.str("category_slug").orEmpty().ifBlank { presetCategory })
    }
    var name by remember(element) { mutableStateOf(element?.str("name").orEmpty()) }
    var description by remember(element) { mutableStateOf(element?.str("description").orEmpty()) }
    var saving by remember { mutableStateOf(false) }

    FormDialog(
        open = true,
        title = t(if (element == null) "new_item" else "edit"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val payload = body(
                    "category_slug" to slug,
                    "name" to name.trim(),
                    "description" to description.trim(),
                )
                val crud = ContinuityCrud(ctx.api, "breakdown-elements")
                val done = ctx.write {
                    if (element == null) crud.create(payload.dropEmpty()) else crud.update(element.id, payload)
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = name.isNotBlank() && slug.isNotBlank(),
        busy = saving,
        width = FORM_WIDTH,
    ) {
        FormGrid {
            PickInput(
                value = slug,
                options = categories.map { it.str("slug") to it.str("name") },
                onChange = { slug = it },
                label = t("category"),
            )
            TextInput(name, { name = it }, t("name"))
            TextInput(description, { description = it }, t("description"), FormWide, multiline = true)
        }
    }
}

private val SEARCH_WIDTH = 240.dp
private val FILTER_WIDTH = 220.dp
private val CATEGORY_COL = 160.dp
private val SCENES_COL = 90.dp
private val ACTIONS_COL = 64.dp
private val FORM_WIDTH = 560.dp
