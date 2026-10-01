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

private val TILE_WIDTH = 170.dp
private val SEARCH_WIDTH = 420.dp

/** Everything the budget reads: the report (`expenses`, `by_category`, inventory and rental figures) and the pickers' lists. */
private class BudgetData(val report: Rec, val characters: List<Rec>, val scenes: List<Rec>, val vendors: List<Rec>)

private fun cast(c: Rec): String = if (c.has("cast_number") && c.str("cast_number").isNotEmpty()) "${c.str("cast_number")}. ${c.str("name")}" else c.str("name")

/**
 * Budget: spend for the whole production, scene by scene, by character and by account code — plus the Full
 * budget, which is all of those at once and the printable top sheet. Finance roles only.
 *
 * A tapped spend tile narrows every tab to that category; tap it again (or Total spend) for everything.
 * Totals are summed per currency, so a line in pounds is never added into a rupee total. Not ported: the
 * per-line chat / share / "send a request" actions (the shared record-actions component) and the sheet
 * template download (a raw file the api client does not fetch).
 */
@Composable
fun BudgetScreen() {
    val ctx = LocalSync.current
    // Only once the role AND the finance list are in: before that, isFinance is false for everyone.
    if (ctx.project.rec != null && ctx.meta != null && !ctx.isFinance) {
        EmptyState(t("csync_budget_finance_only"), t("csync_budget_finance_only_hint"))
        return
    }
    val data = rememberResource {
        val report = api.get("/reports/budget")
        val characters = api.get("/characters").mapRows()
        val scenes = api.get("/scenes").mapRows()
        val vendors = api.get("/vendors").mapRows()
        if (report is ZillitResult.Failure) {
            report
        } else {
            ZillitResult.Success(
                BudgetData(
                    (report as ZillitResult.Success).data.rec ?: Rec.Empty,
                    (characters as? ZillitResult.Success)?.data.orEmpty(),
                    (scenes as? ZillitResult.Success)?.data.orEmpty(),
                    (vendors as? ZillitResult.Success)?.data.orEmpty(),
                ),
            )
        }
    }
    SocketRefresh(SyncEvents.Expense) { data.reload(silent = true) }
    var tab by remember { mutableStateOf(ctx.nav.current.arg("tab").takeIf { it in BudgetModel.BUDGET_TABS } ?: "all") }
    var cat by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var lineOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var uploadOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Rec?>(null) }
    val holder = remember { BudgetFormHolder() }

    Await(data) { book ->
        val view = BudgetView(ctx, book, cat, q)
        BudgetHeader(ctx, onUpload = { uploadOpen = true }, onAdd = { editing = null; lineOpen = true })
        BudgetTiles(ctx, view, cat) { cat = it }
        KitTabs(
            listOf(
                "all" to "${t("csync_budget_tab_all")} (${view.expenses.size})",
                "scenes" to "${t("csync_budget_tab_scenes")} (${book.scenes.size})",
                "characters" to "${t("csync_budget_tab_characters")} (${book.characters.size})",
                "accounts" to "${t("csync_budget_tab_accounts")} (${view.accountCount})",
                "full" to t("csync_budget_full"),
            ),
            tab,
        ) { tab = it; q = "" }
        Column(Modifier.padding(top = ZillitTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            if (tab != "full") {
                ZillitSearchField(
                    q, { q = it },
                    Modifier.width(SEARCH_WIDTH),
                    placeholder = t(mapOf("all" to "csync_budget_search_all", "scenes" to "csync_budget_search_scenes", "characters" to "csync_budget_search_characters", "accounts" to "csync_budget_search_accounts").getValue(tab)),
                )
            }
            if (cat.isNotEmpty()) CategoryNote(cat, view) { cat = "" }
            if (tab == "all" && view.needle.isNotEmpty()) {
                MutedText(t("csync_budget_matching") + " · " + view.total(view.shown) + " " + kitCount("csync_budget_across_n_expenses", view.shown.size))
            }
            val onEdit: ((Rec) -> Unit)? = if (ctx.canPost) { l -> editing = l; lineOpen = true } else null
            val actions: (@Composable (Rec) -> Unit)? = if (ctx.canPost) { l -> DeleteMark { deleting = l } } else null
            if (tab == "full") FullTab(ctx, view, onEdit, actions) else SectionCard(flush = true) { TabBody(tab, view, book, onEdit, actions) }
        }
        BudgetDialogs(ctx, book, view, lineOpen, editing, uploadOpen, holder, deleting, { lineOpen = false }, { uploadOpen = false }, { deleting = null }) { data.reload(silent = true) }
    }
}

@Composable
private fun BudgetHeader(ctx: SyncCtx, onUpload: () -> Unit, onAdd: () -> Unit) {
    PageHead(
        title = t("csync_nav_budget"),
        sub = t("csync_budget_sub"),
        actions = {
            if (ctx.canPost) {
                ZillitButton(t("csync_upload_budget_sheet"), onClick = onUpload, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Upload)
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
            needle, e.str("description"), humanize(e.str("category")), e.str("account_code"), e.str("account_name"), e.str("payee"),
            e.rec("character")?.str("name"), e.rec("scene")?.str("number"), e.rec("costume")?.str("asset_number"), e.rec("costume")?.str("name"), e.rec("vendor")?.str("name"),
        )
    }
    val accountCount: Int = expenses.map { it.str("account_code") }.filter { it.isNotEmpty() }.distinct().size
    val shownScenes: List<Rec> = book.scenes.filter { matches(needle, it.str("number"), it.str("name"), it.str("location")) }
    val shownChars: List<Rec> = book.characters.filter { matches(needle, it.str("name"), it.rec("actor")?.str("name"), it.str("cast_number")) }
    val sceneGroups: List<BudgetGroup> = shownScenes
        .map { s -> BudgetGroup(s.id, listOf("${t("csync_sc")} ${s.str("number")}", s.str("name").ifEmpty { s.str("location") }).filter { it.isNotEmpty() }.joinToString(" - "), expenses.filter { it.str("scene_id") == s.id }) }
        .filter { it.lines.isNotEmpty() } +
        (if (needle.isEmpty() && expenses.any { it.str("scene_id").isEmpty() }) listOf(BudgetGroup(BudgetModel.NONE, t("csync_budget_no_scene"), expenses.filter { it.str("scene_id").isEmpty() })) else emptyList())
    val charGroups: List<BudgetGroup> = shownChars.map { c -> BudgetGroup(c.id, cast(c), expenses.filter { it.str("character_id") == c.id }) }.filter { it.lines.isNotEmpty() } +
        (if (needle.isEmpty() && expenses.any { it.str("character_id").isEmpty() }) listOf(BudgetGroup(BudgetModel.NONE, t("csync_budget_no_character"), expenses.filter { it.str("character_id").isEmpty() })) else emptyList())
    val idleScenes: Int = shownScenes.count { s -> expenses.none { it.str("scene_id") == s.id } }
    val idleChars: Int = shownChars.count { c -> expenses.none { it.str("character_id") == c.id } }
    val deptGroups: List<BudgetGroup> = BudgetModel.departmentGroups(
        expenses.filter { matches(needle, it.str("account_code"), it.str("account_name"), it.str("description"), it.str("payee"), humanize(it.str("category"))) },
        t("csync_budget_no_account_code"),
    )
    val knownAccounts: List<BudgetAccount> = all.filter { it.str("account_code").isNotEmpty() && it.str("account_name").isNotEmpty() }.map { BudgetAccount(it.str("account_code"), it.str("account_name")) }

    /** The chart's categories, plus any this production named itself. */
    val cats: List<String> = (ctx.metaList("expense_categories") + (book.report.rec("by_category")?.keys ?: emptySet())).distinct()

    fun total(lines: List<Rec>) = BudgetModel.sumByCurrency(lines, currency)
}

@Composable
private fun BudgetTiles(ctx: SyncCtx, view: BudgetView, cat: String, onCat: (String) -> Unit) {
    val tile = Modifier.width(TILE_WIDTH)
    FlowRow(Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.md), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        StatCard(t("csync_budget_total_spend"), view.total(view.all), tile, onClick = { onCat("") })
        view.cats.forEach { c ->
            StatCard(tEnum(c), view.total(view.all.filter { it.str("category") == c }), tile, onClick = { onCat(if (cat == c) "" else c) })
        }
        StatCard(t("csync_budget_inventory_value"), BudgetModel.fmtAmount(view.book.report.double("inventory_value"), view.currency), tile, hint = t("csync_budget_inventory_value_hint")) { ctx.nav.go("costumes") }
        StatCard(t("csync_budget_rental_committed"), BudgetModel.fmtAmount(view.book.report.double("rental_committed"), view.currency), tile, hint = t("csync_budget_rental_committed_hint")) { ctx.nav.go("vendors") }
    }
}

@Composable
private fun CategoryNote(cat: String, view: BudgetView, onClear: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
        MutedText("${t("csync_budget_showing_only")} ${tEnum(cat)} ${t("csync_budget_only")} · ${view.total(view.expenses)} ${kitCount("csync_budget_across_n_lines", view.expenses.size)}")
        ZillitButton(t("csync_budget_show_all_categories"), onClick = onClear, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
    }
}

@Composable
private fun DeleteMark(onAsk: () -> Unit) {
    ZillitButton(t("csync_delete"), onClick = onAsk, variant = ButtonVariant.Tertiary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Trash)
}

@Composable
private fun TabBody(tab: String, view: BudgetView, book: BudgetData, onEdit: ((Rec) -> Unit)?, actions: (@Composable (Rec) -> Unit)?) {
    when (tab) {
        "all" -> when {
            view.expenses.isEmpty() -> EmptyState(t("csync_budget_empty"), t("csync_budget_empty_hint"))
            view.shown.isEmpty() -> EmptyState(t("csync_budget_no_match"))
            else -> BudgetSheet(listOf(BudgetGroup("all", t("csync_budget_all_lines"), view.shown)), view.currency, onEdit, lineActions = actions)
        }
        "scenes" -> if (book.scenes.isEmpty()) {
            EmptyState(t("csync_scenes_empty_title"))
        } else {
            BudgetSheet(view.sceneGroups, view.currency, onEdit, empty = {
                EmptyState(if (view.needle.isNotEmpty()) t("csync_scenes_no_match") else t("csync_budget_no_scene_spend"), t("csync_budget_tag_scene_hint"))
            })
            if (view.idleScenes > 0) MutedText(kitCount("csync_budget_idle_scenes", view.idleScenes), Modifier.padding(ZillitTheme.spacing.md))
        }
        "accounts" -> BudgetSheet(view.deptGroups, view.currency, onEdit, empty = {
            EmptyState(if (view.needle.isNotEmpty()) t("csync_budget_no_accounts_match") else t("csync_budget_empty"), t("csync_budget_account_hint"))
        })
        else -> if (book.characters.isEmpty()) {
            EmptyState(t("csync_characters_empty_title"))
        } else {
            BudgetSheet(view.charGroups, view.currency, onEdit, empty = {
                EmptyState(if (view.needle.isNotEmpty()) t("csync_characters_no_match") else t("csync_budget_no_character_spend"), t("csync_budget_tag_character_hint"))
            })
            if (view.idleChars > 0) MutedText(kitCount("csync_budget_idle_characters", view.idleChars), Modifier.padding(ZillitTheme.spacing.md))
        }
    }
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
    SectionCard(title = t("csync_budget_all_lines"), flush = true) { BudgetSheet(listOf(BudgetGroup("all", t("csync_budget_all_lines"), expenses)), view.currency, onEdit, lineActions = actions) }
    if (view.sceneGroups.isNotEmpty()) SectionCard(title = t("csync_budget_tab_scenes"), flush = true) { BudgetSheet(view.sceneGroups, view.currency, onEdit) }
    if (view.charGroups.isNotEmpty()) SectionCard(title = t("csync_budget_tab_characters"), flush = true) { BudgetSheet(view.charGroups, view.currency, onEdit) }
    if (view.deptGroups.isNotEmpty()) SectionCard(title = t("csync_budget_tab_accounts"), flush = true) { BudgetSheet(view.deptGroups, view.currency, onEdit) }
}

private fun printBudget(ctx: SyncCtx, view: BudgetView) {
    val groups = view.deptGroups.ifEmpty { listOf(BudgetGroup("all", t("csync_budget_all_lines"), view.expenses)) }
    val html = budgetHtml(ctx.project.rec, view.expenses, groups, view.currency)
    val name = ctx.project.name.ifEmpty { t("csync_production") }
    ctx.scope.launch { ctx.host.save("${PrintHtml.slug("$name ${t("csync_budget_word")}")}.html", PrintHtml.page("$name · ${t("csync_budget_word")}", html).encodeToByteArray()) }
}

@Composable
private fun BudgetDialogs(
    ctx: SyncCtx,
    book: BudgetData,
    view: BudgetView,
    lineOpen: Boolean,
    editing: Rec?,
    uploadOpen: Boolean,
    holder: BudgetFormHolder,
    deleting: Rec?,
    onCloseLine: () -> Unit,
    onCloseUpload: () -> Unit,
    onCloseDelete: () -> Unit,
    reload: () -> Unit,
) {
    BudgetUpload(uploadOpen, onCloseUpload, reload, view.currency)
    BudgetLineDialog(
        open = lineOpen,
        editing = editing,
        context = BudgetLineContext(view.currency, view.cats, view.knownAccounts, book.scenes, book.characters, book.vendors),
        remembered = holder,
        onClose = onCloseLine,
        onSaved = reload,
    )
    KitConfirm(
        open = deleting != null,
        title = t("csync_budget_delete_line_confirm"),
        body = deleting?.let { BudgetModel.lineTitle(it, t("csync_budget_line")) + " · " + BudgetModel.fmtAmount(it.double("amount"), it.str("currency").ifEmpty { view.currency }) + (it.long("date").takeIf { d -> d != 0L }?.let { d -> " · " + fmtDate(d) } ?: "") }.orEmpty(),
        confirmLabel = t("csync_delete"),
        onConfirm = {
            val id = deleting?.id.orEmpty()
            onCloseDelete()
            ctx.launchWrite({ ctx.api.delete("/expenses/$id") }, onDone = { reload() })
        },
        onDismiss = onCloseDelete,
    )
}
