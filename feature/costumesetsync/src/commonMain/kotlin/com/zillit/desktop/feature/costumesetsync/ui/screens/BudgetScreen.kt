package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.BudgetAccount
import com.zillit.desktop.feature.costumesetsync.domain.BudgetGroup
import com.zillit.desktop.feature.costumesetsync.domain.BudgetModel
import com.zillit.desktop.feature.costumesetsync.domain.PrintHtml
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.fmtDate
import com.zillit.desktop.feature.costumesetsync.domain.humanize
import com.zillit.desktop.feature.costumesetsync.domain.matches
import com.zillit.desktop.feature.costumesetsync.ui.AutoFillGrid
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatCard
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch

private val TILE_WIDTH = 120.dp
private val TILE_GAP = 10.dp
private val SEARCH_WIDTH = 420.dp

/**
 * Everything the budget reads: the report (`expenses`, `by_category`, inventory and rental figures) and the pickers'
 * lists.
 */
private class BudgetData(val report: Rec, val characters: List<Rec>, val scenes: List<Rec>, val vendors: List<Rec>)

private fun cast(c: Rec): String = if (c.has("cast_number") &&
    c.str("cast_number").isNotEmpty()) "${c.str("cast_number")}. ${c.str("name")}" else c.str("name")

/**
 * Budget: spend for the whole production, scene by scene, by character and by account code — plus the Full
 * budget, which is all of those at once and the printable top sheet. Finance roles only.
 *
 * A tapped spend tile narrows every tab to that category; tap it again (or Total spend) for everything.
 * Totals are summed per currency, so a line in pounds is never added into a rupee total. Not ported: "send a request"
 * on a line and the sheet template download (a raw file the api client does not fetch).
 */
/** What the budget screen has open and typed, in one place so its pieces can share it. */
private class BudgetUi(initialTab: String) {
    var tab by mutableStateOf(initialTab)
    var cat by mutableStateOf("")
    var q by mutableStateOf("")
    var lineOpen by mutableStateOf(false)
    var editing by mutableStateOf<Rec?>(null)
    var uploadOpen by mutableStateOf(false)
    var deleting by mutableStateOf<Rec?>(null)

    fun openLine(line: Rec?) {
        editing = line
        lineOpen = true
    }
}

private suspend fun SyncCtx.loadBudgetData(): ZillitResult<BudgetData> {
    val report = api.get("/reports/budget")
    val characters = api.get("/characters").mapRows()
    val scenes = api.get("/scenes").mapRows()
    val vendors = api.get("/vendors").mapRows()
    if (report is ZillitResult.Failure) return report
    return ZillitResult.Success(
        BudgetData(
            (report as ZillitResult.Success).data.rec ?: Rec.Empty,
            (characters as? ZillitResult.Success)?.data.orEmpty(),
            (scenes as? ZillitResult.Success)?.data.orEmpty(),
            (vendors as? ZillitResult.Success)?.data.orEmpty(),
        ),
    )
}

@Composable
fun BudgetScreen() {
    val ctx = LocalSync.current
    // Only once the role AND the finance list are in: before that, isFinance is false for everyone.
    if (ctx.project.rec != null && ctx.meta != null && !ctx.isFinance) {
        EmptyState(t("csync_budget_finance_only"), t("csync_budget_finance_only_hint"))
        return
    }
    val data = rememberResource { loadBudgetData() }
    SocketRefresh(SyncEvents.Expense) { data.reload(silent = true) }
    val ui = remember { BudgetUi(ctx.nav.current.arg("tab").takeIf { it in BudgetModel.BUDGET_TABS } ?: "all") }
    val holder = remember { BudgetFormHolder() }
    val counts = rememberCommentCounts("EXPENSE") + rememberCommentCounts("BUDGET")
    Await(data) { book ->
        androidx.compose.runtime.CompositionLocalProvider(LocalRecordCounts provides counts) {
            BudgetBody(ctx, book, ui, holder) { data.reload(silent = true) }
        }
    }
}

@Composable
private fun BudgetBody(ctx: SyncCtx, book: BudgetData, ui: BudgetUi, holder: BudgetFormHolder, reload: () -> Unit) {
    val view = BudgetView(ctx, book, ui.cat, ui.q)
    BudgetHeader(ctx, onUpload = { ui.uploadOpen = true }, onAdd = { ui.openLine(null) })
    BudgetTiles(ctx, view, ui.cat) { ui.cat = it }
    KitTabs(
        listOf(
            "all" to "${t("csync_budget_tab_all")} (${view.expenses.size})",
            "scenes" to "${t("csync_budget_tab_scenes")} (${book.scenes.size})",
            "characters" to "${t("csync_budget_tab_characters")} (${book.characters.size})",
            "accounts" to "${t("csync_budget_tab_accounts")} (${view.accountCount})",
            "full" to t("csync_budget_full"),
        ),
        ui.tab,
    ) {
        ui.tab = it
        ui.q = ""
    }
    Column(
        Modifier.padding(top = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        BudgetFilters(ui, view)
        val onEdit: ((Rec) -> Unit)? = if (ctx.canPost) { l -> ui.openLine(l) } else null
        val actions: (@Composable (Rec) -> Unit)? = { l ->
            RecordActions(
                "EXPENSE",
                l.id,
                BudgetModel.lineTitle(l, t("csync_budget_line")),
                expenseSummary(l, view.currency),
            )
            if (ctx.canPost) DeleteMark { ui.deleting = l }
        }
        if (ui.tab == "full") FullTab(ctx, view, onEdit, actions) else SectionCard(flush = true) {
            TabBody(ui.tab, view, book, onEdit, actions)
        }
    }
    BudgetDialogs(ctx, book, view, ui, holder, reload)
}

@Composable
private fun BudgetFilters(ui: BudgetUi, view: BudgetView) {
    if (ui.tab != "full") {
        ZillitSearchField(
            ui.q,
            { ui.q = it },
            Modifier.width(SEARCH_WIDTH),
            placeholder = t(
                mapOf(
                    "all" to "csync_budget_search_all",
                    "scenes" to "csync_budget_search_scenes",
                    "characters" to "csync_budget_search_characters",
                    "accounts" to "csync_budget_search_accounts",
                ).getValue(ui.tab),
            ),
        )
    }
    if (ui.cat.isNotEmpty()) CategoryNote(ui.cat, view) { ui.cat = "" }
    if (ui.tab == "all" && view.needle.isNotEmpty()) {
        MutedText(
            t("csync_budget_matching") + " · " + view.total(view.shown) + " " + kitCount(
                "csync_budget_across_n_expenses",
                view.shown.size,
            ),
        )
    }
}

/** The web's `summaryOf`: what a share or a chat about one expense line carries. */
private fun expenseSummary(e: Rec, currency: String): String =
    "${t("csync_budget_expense")}: ${BudgetModel.lineTitle(e, t("csync_budget_line"))} · " +
        "${BudgetModel.fmtAmount(e.double("amount"), e.str("currency").ifEmpty { currency })}\n" +
        listOfNotNull(
            e.str("account_code").ifEmpty { null },
            e.long("date").takeIf { it != 0L }?.let { fmtDate(it) },
            tEnum(e.str("category")).ifEmpty { null },
            e.rec("scene")?.let { "${t("csync_sc")} ${it.str("number")}" },
            e.rec("character")?.str("name")?.ifEmpty { null },
            e.rec("vendor")?.str("name")?.ifEmpty { null },
        ).joinToString(" · ")

@Composable
private fun BudgetHeader(ctx: SyncCtx, onUpload: () -> Unit, onAdd: () -> Unit) {
    PageHead(
        title = t("csync_nav_budget"),
        sub = t("csync_budget_sub"),
        actions = {
            if (ctx.canPost) {
                ZillitButton(
                    t("csync_upload_budget_sheet"),
                    onClick = onUpload,
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Upload,
                )
                ZillitButton(t("csync_nav_budget"), onClick = onAdd, leadingIcon = ZillitIcons.Add)
            }
        },
    )
}

/** The filtered lines and the groupings every tab reads. */
private class BudgetView(val ctx: SyncCtx, val book: BudgetData, val cat: String, q: String) {
    val currency: String = ctx.project.currency.ifEmpty { ctx.currency }
    val needle = q.trim()
    val all: List<Rec> = book.report.recs("expenses")
    val expenses: List<Rec> = if (cat.isEmpty()) all else all.filter { it.str("category") == cat }
    val shown: List<Rec> = expenses.filter { e ->
        matches(
            needle,
            e.str("description"),
            humanize(e.str("category")),
            e.str("account_code"),
            e.str("account_name"),
            e.str("payee"),
            e.rec("character")?.str("name"),
            e.rec("scene")?.str("number"),
            e.rec("costume")?.str("asset_number"),
            e.rec("costume")?.str("name"),
            e.rec("vendor")?.str("name"),
        )
    }
    val accountCount: Int = expenses.map { it.str("account_code") }.filter { it.isNotEmpty() }.distinct().size
    val shownScenes: List<Rec> = book.scenes.filter {
        matches(needle, it.str("number"), it.str("name"), it.str("location"))
    }
    val shownChars: List<Rec> = book.characters.filter {
        matches(needle, it.str("name"), it.rec("actor")?.str("name"), it.str("cast_number"))
    }
    val sceneGroups: List<BudgetGroup> = shownScenes
        .map { s ->
            BudgetGroup(
                s.id,
                listOf(
                    "${t("csync_sc")} ${s.str("number")}",
                    s.str("name").ifEmpty { s.str("location") },
                ).filter { it.isNotEmpty() }.joinToString(" - "),
                expenses.filter { it.str("scene_id") == s.id },
            )
        }
        .filter { it.lines.isNotEmpty() } +
        (if (needle.isEmpty() && expenses.any { it.str("scene_id").isEmpty() }) listOf(
            BudgetGroup(BudgetModel.NONE, t("csync_budget_no_scene"), expenses.filter { it.str("scene_id").isEmpty() }),
        ) else emptyList())
    val charGroups: List<BudgetGroup> = shownChars.map { c -> BudgetGroup(
        c.id,
        cast(c),
        expenses.filter { it.str("character_id") == c.id },
    ) }.filter { it.lines.isNotEmpty() } +
        (if (needle.isEmpty() && expenses.any { it.str("character_id").isEmpty() }) listOf(
            BudgetGroup(
                BudgetModel.NONE,
                t("csync_budget_no_character"),
                expenses.filter { it.str("character_id").isEmpty() },
            ),
        ) else emptyList())
    val idleScenes: Int = shownScenes.count { s -> expenses.none { it.str("scene_id") == s.id } }
    val idleChars: Int = shownChars.count { c -> expenses.none { it.str("character_id") == c.id } }
    val deptGroups: List<BudgetGroup> = BudgetModel.departmentGroups(
        expenses.filter { matches(
            needle,
            it.str("account_code"),
            it.str("account_name"),
            it.str("description"),
            it.str("payee"),
            humanize(it.str("category")),
        ) },
        t("csync_budget_no_account_code"),
    )
    val knownAccounts: List<BudgetAccount> = all
        .filter { it.str("account_code").isNotEmpty() && it.str("account_name").isNotEmpty() }
        .map {
        BudgetAccount(it.str("account_code"), it.str("account_name"))
    }

    /** The chart's categories, plus any this production named itself. */
    val cats: List<String> = (ctx.metaList("expense_categories") +
        (book.report.rec("by_category")?.keys ?: emptySet())).distinct()

    fun total(lines: List<Rec>) = BudgetModel.sumByCurrency(lines, currency)
}

@Composable
private fun BudgetTiles(ctx: SyncCtx, view: BudgetView, cat: String, onCat: (String) -> Unit) {
    // `.csync-stats--compact`: auto-fill, minmax(120px, 1fr), 10px apart — the tiles stretch to share the row.
    class Tile(
        val label: String,
        val value: String,
        val hint: String? = null,
        val active: Boolean = false,
        val go: () -> Unit,
    )
    val tiles = buildList {
        add(Tile(t("csync_budget_total_spend"), view.total(view.all), active = cat.isEmpty()) { onCat("") })
        view.cats.forEach { c ->
            add(
                Tile(
                    tEnum(c),
                    view.total(view.all.filter { it.str("category") == c }),
                    active = cat == c,
                ) { onCat(if (cat == c) "" else c) },
            )
        }
        add(
            Tile(
                t("csync_budget_inventory_value"),
                BudgetModel.fmtAmount(view.book.report.double("inventory_value"), view.currency),
                t("csync_budget_inventory_value_hint"),
            ) { ctx.nav.go("costumes") },
        )
        add(
            Tile(
                t("csync_budget_rental_committed"),
                BudgetModel.fmtAmount(view.book.report.double("rental_committed"), view.currency),
                t("csync_budget_rental_committed_hint"),
            ) { ctx.nav.go("vendors") },
        )
    }
    AutoFillGrid(tiles.size, TILE_WIDTH, TILE_GAP, Modifier.padding(bottom = ZillitTheme.spacing.md)) { i, cell ->
        val tile = tiles[i]
        StatCard(
            tile.label,
            tile.value,
            cell,
            hint = tile.hint,
            compact = true,
            active = tile.active,
            onClick = tile.go,
        )
    }
}

@Composable
private fun CategoryNote(cat: String, view: BudgetView, onClear: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MutedText(
            "${t("csync_budget_showing_only")} ${tEnum(cat)} ${t("csync_budget_only")} · " +
            "${view.total(view.expenses)} ${kitCount("csync_budget_across_n_lines", view.expenses.size)}",
        )
        ZillitButton(
            t("csync_budget_show_all_categories"),
            onClick = onClear,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
    }
}

@Composable
private fun DeleteMark(onAsk: () -> Unit) {
    com.zillit.desktop.core.designsystem.component.ZillitIconButton(
        ZillitIcons.Trash,
        t("csync_delete"),
        onAsk,
        tint = ZillitTheme.colors.textSecondary,
        size = 28.dp,
    )
}

@Composable
private fun TabBody(
    tab: String,
    view: BudgetView,
    book: BudgetData,
    onEdit: ((Rec) -> Unit)?,
    actions: (@Composable (Rec) -> Unit)?,
) {
    when (tab) {
        "all" -> when {
            view.expenses.isEmpty() -> EmptyState(t("csync_budget_empty"), t("csync_budget_empty_hint"))
            view.shown.isEmpty() -> EmptyState(t("csync_budget_no_match"))
            else -> BudgetSheet(
                listOf(BudgetGroup("all", t("csync_budget_all_lines"), view.shown)),
                view.currency,
                onEdit,
                lineActions = actions,
            )
        }
        "scenes" -> GroupedTab(
            hasRows = book.scenes.isNotEmpty(),
            emptyTitle = t("csync_scenes_empty_title"),
            groups = view.sceneGroups,
            view = view,
            onEdit = onEdit,
            noMatch = t("csync_scenes_no_match"),
            noSpend = t("csync_budget_no_scene_spend"),
            hint = t("csync_budget_tag_scene_hint"),
            idle = if (view.idleScenes > 0) kitCount("csync_budget_idle_scenes", view.idleScenes) else null,
        )
        "accounts" -> BudgetSheet(view.deptGroups, view.currency, onEdit, empty = {
            EmptyState(
                if (view.needle.isNotEmpty()) t("csync_budget_no_accounts_match") else t("csync_budget_empty"),
                t("csync_budget_account_hint"),
            )
        })
        else -> GroupedTab(
            hasRows = book.characters.isNotEmpty(),
            emptyTitle = t("csync_characters_empty_title"),
            groups = view.charGroups,
            view = view,
            onEdit = onEdit,
            noMatch = t("csync_characters_no_match"),
            noSpend = t("csync_budget_no_character_spend"),
            hint = t("csync_budget_tag_character_hint"),
            idle = if (view.idleChars > 0) kitCount("csync_budget_idle_characters", view.idleChars) else null,
        )
    }
}

/** The Scenes and Characters tabs: one sheet of groups, with a note of how many have no spend yet. */
@Composable
private fun GroupedTab(
    hasRows: Boolean,
    emptyTitle: String,
    groups: List<BudgetGroup>,
    view: BudgetView,
    onEdit: ((Rec) -> Unit)?,
    noMatch: String,
    noSpend: String,
    hint: String,
    idle: String?,
) {
    if (!hasRows) {
        EmptyState(emptyTitle)
        return
    }
    BudgetSheet(groups, view.currency, onEdit, empty = {
        EmptyState(if (view.needle.isNotEmpty()) noMatch else noSpend, hint)
    })
    if (idle != null) MutedText(idle, Modifier.padding(ZillitTheme.spacing.md))
}

@Composable
private fun FullTab(ctx: SyncCtx, view: BudgetView, onEdit: ((Rec) -> Unit)?, actions: (@Composable (Rec) -> Unit)?) {
    val expenses = view.expenses
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ZillitText(view.total(expenses), style = ZillitTheme.typography.titleLarge)
                MutedText(
                    listOf(
                        kitCount("csync_budget_n_lines", expenses.size),
                        kitCount("csync_budget_n_scenes", view.sceneGroups.size),
                        kitCount("csync_budget_n_characters", view.charGroups.size),
                        kitCount("csync_budget_n_account_groups", view.deptGroups.size),
                    ).joinToString(" · "),
                )
            }
            val name = ctx.project.name.ifEmpty { t("csync_production") }
            RecordActions(
                "BUDGET",
                ctx.project.rec?.id?.ifEmpty { null } ?: "budget",
                "$name · ${t("csync_budget_word")}",
                "$name · ${t("csync_budget_word")}\n${t("csync_budget_grand_total")}: ${view.total(expenses)}\n" +
                    view.deptGroups.joinToString("\n") { g -> "${g.title}: ${view.total(g.lines)}" },
            )
            ZillitButton(
                t("csync_print_pdf"),
                onClick = { ctx.whenDownload { printBudget(ctx, view) } },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Print,
            )
        }
    }
    if (expenses.isEmpty()) {
        SectionCard(flush = true) { EmptyState(t("csync_budget_empty"), t("csync_budget_empty_full_hint")) }
        return
    }
    SectionCard(title = t("csync_budget_all_lines"), flush = true) {
        BudgetSheet(
            listOf(BudgetGroup("all", t("csync_budget_all_lines"), expenses)),
            view.currency,
            onEdit,
            lineActions = actions,
        )
    }
    if (view.sceneGroups.isNotEmpty()) SectionCard(title = t("csync_budget_tab_scenes"), flush = true) {
        BudgetSheet(view.sceneGroups, view.currency, onEdit)
    }
    if (view.charGroups.isNotEmpty()) SectionCard(title = t("csync_budget_tab_characters"), flush = true) {
        BudgetSheet(view.charGroups, view.currency, onEdit)
    }
    if (view.deptGroups.isNotEmpty()) SectionCard(title = t("csync_budget_tab_accounts"), flush = true) {
        BudgetSheet(view.deptGroups, view.currency, onEdit)
    }
}

private fun printBudget(ctx: SyncCtx, view: BudgetView) {
    val groups = view.deptGroups.ifEmpty { listOf(BudgetGroup("all", t("csync_budget_all_lines"), view.expenses)) }
    val html = budgetHtml(ctx.project.rec, view.expenses, groups, view.currency)
    val name = ctx.project.name.ifEmpty { t("csync_production") }
    ctx.scope.launch {
        ctx.host.save(
            "${PrintHtml.slug("$name ${t("csync_budget_word")}")}.html",
            PrintHtml.page("$name · ${t("csync_budget_word")}", html).encodeToByteArray(),
        )
    }
}

private fun deleteBody(line: Rec, currency: String): String {
    val amount = BudgetModel.fmtAmount(line.double("amount"), line.str("currency").ifEmpty { currency })
    val date = line.long("date").takeIf { it != 0L }?.let { " · " + fmtDate(it) }.orEmpty()
    return BudgetModel.lineTitle(line, t("csync_budget_line")) + " · " + amount + date
}

@Composable
private fun BudgetDialogs(
    ctx: SyncCtx,
    book: BudgetData,
    view: BudgetView,
    ui: BudgetUi,
    holder: BudgetFormHolder,
    reload: () -> Unit,
) {
    val deleting = ui.deleting
    val onCloseDelete = { ui.deleting = null }
    BudgetUpload(ui.uploadOpen, { ui.uploadOpen = false }, reload, view.currency)
    BudgetLineDialog(
        open = ui.lineOpen,
        editing = ui.editing,
        context = BudgetLineContext(
            view.currency,
            view.cats,
            view.knownAccounts,
            book.scenes,
            book.characters,
            book.vendors,
        ),
        remembered = holder,
        onClose = { ui.lineOpen = false },
        onSaved = reload,
    )
    KitConfirm(
        open = deleting != null,
        title = t("csync_budget_delete_line_confirm"),
        body = deleting?.let { deleteBody(it, view.currency) }.orEmpty(),
        confirmLabel = t("csync_delete"),
        onConfirm = {
            val id = deleting?.id.orEmpty()
            onCloseDelete()
            ctx.launchWrite({ ctx.api.delete("/expenses/$id") }, onDone = { reload() })
        },
        onDismiss = onCloseDelete,
    )
}
