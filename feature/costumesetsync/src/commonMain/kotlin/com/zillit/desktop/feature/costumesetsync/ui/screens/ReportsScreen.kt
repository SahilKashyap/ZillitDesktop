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
import com.zillit.desktop.core.designsystem.component.ZillitText
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.DayKeys
import com.zillit.desktop.feature.costumesetsync.domain.PrintHtml
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.ReportsModel
import com.zillit.desktop.feature.costumesetsync.domain.Tone
import com.zillit.desktop.feature.costumesetsync.domain.fmtMoney
import com.zillit.desktop.feature.costumesetsync.domain.todayParam
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatCard
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch

private val TILE_WIDTH = 130.dp

/** Anything that moves a figure on one of the three reports. */
private val REPORT_EVENTS = SyncEvents.Costume + SyncEvents.Cleaning + SyncEvents.Alteration + SyncEvents.Damage +
    SyncEvents.Missing +
    SyncEvents.Scene + SyncEvents.Change + SyncEvents.Character + SyncEvents.Vendor + SyncEvents.Expense +
        SyncEvents.Settings

/**
 * Reports, as the reference app's: the wardrobe daily report for a date, the asset
 * inventory and the wrap report, one tab each, with CSV and Print / PDF for the open tab.
 *
 * Every figure comes from the service's `/reports/daily`, `/reports/inventory` and
 * `/reports/wrap`. Both CSVs are built here from the figures on screen (the web's
 * daily CSV is the service's raw `/reports/daily.csv`, which the desktop api client
 * does not fetch); Print / PDF saves the open tab as an HTML file.
 */
@Composable
fun ReportsScreen() {
    val ctx = LocalSync.current
    var tab by remember { mutableStateOf("daily") }
    var date by remember { mutableStateOf(todayParam(ctx.now())) }
    val report = rememberResource(tab, date) {
        when (tab) {
            "daily" -> api.get("/reports/daily", mapOf("date" to date))
            "inventory" -> api.get("/reports/inventory")
            else -> api.get("/reports/wrap")
        }
    }
    SocketRefresh(REPORT_EVENTS) { report.reload(silent = true) }

    PageHead(
        title = t("csync_reports_title"),
        sub = t("csync_rpt_sub"),
        actions = {
            ZillitButton(
                t("csync_print_pdf"),
                onClick = { ctx.whenDownload { report.value?.let { printReport(ctx, tab, date, it.rec, it.rows) } } },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Print,
                enabled = report.value != null,
            )
        },
    )
    KitTabs(
        listOf(
            "daily" to t("csync_rpt_tab_daily"),
            "inventory" to t("csync_rpt_tab_inventory"),
            "wrap" to t("csync_rpt_tab_wrap"),
        ),
        tab,
        { tab = it },
    )
    Column(
        Modifier.padding(top = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Toolbar(
            ctx,
            tab,
            date,
            { date = it.ifEmpty { todayParam(ctx.now()) } },
            report.value?.rec,
            report.value?.rows.orEmpty(),
        )
        Await(report) { answer ->
            when (tab) {
                "daily" -> DailyReport(answer.rec ?: Rec.Empty, date)
                "inventory" -> InventoryReport(answer.rows)
                else -> WrapReport(answer.rec)
            }
        }
    }
}

@Composable
private fun Toolbar(
    ctx: SyncCtx,
    tab: String,
    date: String,
    onDate: (String) -> Unit,
    daily: Rec?,
    inventory: List<Rec>,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (tab) {
            "daily" -> {
                // No visible label, as the web's DatePicker.
                com.zillit.desktop.core.designsystem.component.ZillitDateField(
                    date,
                    onDate,
                    Modifier.width(KIT_DATE_WIDTH),
                )
                ZillitButton(
                    t("csync_rpt_csv"),
                    onClick = {
                        ctx.whenDownload {
                            daily?.let { saveCsv(ctx, "wardrobe-daily-$date.csv", ReportsModel.dailyCsv(it)) }
                        }
                    },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Download,
                    enabled = daily != null,
                )
            }
            "inventory" -> {
                ZillitButton(
                    t("csync_rpt_csv"),
                    onClick = {
                        ctx.whenDownload {
                            saveCsv(ctx, "inventory.csv", ReportsModel.inventoryCsv(inventory, ctx.isFinance))
                        }
                    },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Download,
                    enabled = inventory.isNotEmpty(),
                )
                MutedText(t("csync_rpt_n_assets", "n" to inventory.size))
            }
            else -> ZillitNotice(
                t("csync_rpt_wrap_notice").replace("{qr}", t("csync_qr_labels")),
                tone = StatusTone.Progress,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun saveCsv(ctx: SyncCtx, name: String, text: String) {
    ctx.scope.launch { ctx.host.save(name, text.encodeToByteArray()) }
}

private fun printReport(ctx: SyncCtx, tab: String, date: String, rec: Rec?, rows: List<Rec>) {
    val body = when (tab) {
        "daily" -> dailyReportHtml(rec ?: Rec.Empty, date, ctx.isFinance, ctx.currency)
        "inventory" -> inventoryReportHtml(rows, ctx.isFinance, ctx.currency)
        else -> wrapReportHtml(rec)
    }
    val name = PrintHtml.slug("${t("csync_reports_title")} $tab" + if (tab == "daily") " $date" else "")
    ctx.scope.launch { ctx.host.save("$name.html", PrintHtml.page(t("csync_reports_title"), body).encodeToByteArray()) }
}

/** Asset number then description, the reference's "mono asset + name" title. */
@Composable
private fun Piece(costume: Rec?, mark: String = "", emergency: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        KitText(
            (if (mark.isNotEmpty()) "$mark " else "") + costume?.str("asset_number").orEmpty(),
            mono = true,
            strong = true,
            maxLines = 1,
        )
        KitText(costume?.str("name").orEmpty() + if (emergency) " 🚨" else "", maxLines = 1)
    }
}

@Composable
private fun DailyStats(s: Rec) {
    val ctx = LocalSync.current
    FlowRow(
        Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        val tile = Modifier.width(TILE_WIDTH)
        StatCard(t("csync_rpt_scenes"), s.long("scenes"), tile, compact = true)
        StatCard(t("csync_rpt_returned"), s.long("returned"), tile, compact = true)
        StatCard(
            t("csync_rpt_cleaning"),
            s.long("cleaning"),
            tile,
            compact = true,
            hint = t("csync_rpt_n_completed", "n" to s.long("cleaning_completed")),
        )
        StatCard(t("csync_rpt_alteration"), s.long("alteration"), tile, compact = true)
        StatCard(t("csync_rpt_damaged"), s.long("damaged"), tile, compact = true)
        StatCard(
            t("csync_rpt_missing"),
            s.long("missing"),
            tile,
            compact = true,
            tone = Tone.Danger.takeIf { s.long("missing") > 0 },
        )
        if (ctx.isFinance) StatCard(
            t("csync_rpt_spend_today"),
            fmtMoney(s.double("spend"), ctx.currency),
            tile,
            compact = true,
        )
    }
}

@Composable
private fun DailyReport(data: Rec, day: String) {
    val s = data.rec("summary") ?: Rec.Empty
    val ctx = LocalSync.current
    // The web puts the heading inside the card body (an `h2`, no header band).
    SectionCard {
        ZillitText(
            t("csync_rpt_daily_title", "name" to data.rec("project")?.str("name").orEmpty()),
            style = ZillitTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        )
        MutedText(
            DayKeys.medium(day) + " · " + t(
                "csync_rpt_shooting_day",
                "n" to (data.rec("project")?.long("shooting_day") ?: 0L),
            ),
        )
        DailyStats(s)
    }
    Column(
        Modifier.padding(top = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ScenesTable(data.recs("scenes"))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            CleaningCard(data.recs("cleaning"), Modifier.weight(1f))
            AltMissingCard(data.recs("alterations"), data.recs("missing"), Modifier.weight(1f))
        }
    }
}

private val SCENE_WEIGHTS = listOf(0.5f, 1.5f, 1.5f, 1f, 3f)

@Composable
private fun ScenesTable(scenes: List<Rec>) {
    SectionCard(title = t("csync_rpt_scenes_card"), flush = true) {
        if (scenes.isEmpty()) {
            MutedText(t("csync_rpt_no_scenes"), Modifier.padding(ZillitTheme.spacing.lg))
            return@SectionCard
        }
        KitHeader(
            listOf(
                t("csync_sc"),
                t("csync_field_name"),
                t("csync_field_location"),
                t("csync_field_status"),
                t("csync_rpt_col_characters"),
            ),
            SCENE_WEIGHTS,
        )
        scenes.forEach { sc ->
            KitGridRow(
                SCENE_WEIGHTS,
                listOf(
                    { KitText(sc.str("number"), strong = true) },
                    { KitText(sc.str("name")) },
                    { KitText(sc.str("location")) },
                    { StatusBadge(sc.str("status"), tEnum(sc.str("status"))) },
                    { KitText(ReportsModel.sceneCast(sc), maxLines = 3) },
                ),
                bold = true,
            )
        }
    }
}

@Composable
private fun CleaningCard(cleaning: List<Rec>, modifier: Modifier) {
    SectionCard(title = t("csync_rpt_cleaning_card"), flush = true, modifier = modifier) {
        if (cleaning.isEmpty()) MutedText(t("csync_none_period"), Modifier.padding(ZillitTheme.spacing.lg))
        cleaning.forEachIndexed { i, c ->
            if (i > 0) ZillitDivider()
            ReportItem(
                c.rec("costume"),
                "",
                c.bool("is_emergency"),
                "${c.str("problem")} · ${tEnum(c.str("cleaning_type"))}",
                c.str("status"),
            )
        }
    }
}

@Composable
private fun AltMissingCard(alterations: List<Rec>, missing: List<Rec>, modifier: Modifier) {
    SectionCard(title = t("csync_rpt_alt_missing_card"), flush = true, modifier = modifier) {
        alterations.forEachIndexed { i, a ->
            if (i > 0) ZillitDivider()
            ReportItem(a.rec("costume"), "✂️", false, "${a.str("issue")} → ${a.str("required_work")}", a.str("status"))
        }
        missing.forEachIndexed { i, m ->
            if (i > 0 || alterations.isNotEmpty()) ZillitDivider()
            ReportItem(
                m.rec("costume"),
                "🔎",
                false,
                t("csync_rpt_last_seen", "x" to m.str("last_seen_location").ifBlank { "—" }),
                "MISSING",
            )
        }
        if (alterations.size + missing.size == 0) MutedText(
            t("csync_none_period"),
            Modifier.padding(ZillitTheme.spacing.lg),
        )
    }
}

@Composable
private fun ReportItem(costume: Rec?, mark: String, emergency: Boolean, meta: String, status: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Piece(costume, mark, emergency)
            MutedText(meta, maxLines = 2)
        }
        StatusBadge(status, tEnum(status))
    }
}

private val INVENTORY_WEIGHTS = listOf(1.1f, 1.8f, 1.4f, 0.6f, 1.2f, 1.1f, 1.2f, 0.9f, 1f, 1.2f)
private val INVENTORY_WEIGHTS_NO_COST = INVENTORY_WEIGHTS.filterIndexed { i, _ -> i != COST_INDEX }
private const val COST_INDEX = 7

/** Every asset with its source, cost (finance only), status and location. */
@Composable
private fun InventoryReport(rows: List<Rec>) {
    val ctx = LocalSync.current
    val weights = if (ctx.isFinance) INVENTORY_WEIGHTS else INVENTORY_WEIGHTS_NO_COST
    SectionCard(flush = true) {
        if (rows.isEmpty()) {
            EmptyState(t("csync_rpt_n_assets", "n" to 0))
            return@SectionCard
        }
        val headers = listOf(
            t("csync_rpt_col_asset"), t("csync_field_description"), t("csync_field_category"), t("csync_field_size"), t(
                "csync_field_character",
            ),
            t("csync_field_source"), t("csync_field_vendor"),
        ) + (if (ctx.isFinance) listOf(t("csync_rpt_col_cost")) else emptyList()) + listOf(t("csync_field_status"),
        t("csync_field_location"))
        KitHeader(headers, weights)
        rows.forEach { r ->
            val cells = buildList<@Composable () -> Unit> {
                add { KitText(r.str("asset"), mono = true, strong = true, maxLines = 1) }
                add { KitText(r.str("name") + ReportsModel.qtySuffix(r.long("quantity"))) }
                add { KitText(tEnum(r.str("category")) + r.str("type").let { if (it.isBlank()) "" else " / $it" }) }
                add { KitText(r.str("size")) }
                add { KitText(r.str("character")) }
                add { KitText(tEnum(r.str("source"))) }
                add { KitText(r.str("vendor")) }
                if (ctx.isFinance) add {
                    KitText(
                        if (r.has("purchase_cost")) fmtMoney(r.double("purchase_cost"), ctx.currency) else "",
                        maxLines = 1,
                    )
                }
                add { StatusBadge(r.str("status"), tEnum(r.str("status"))) }
                add { KitText(r.str("location")) }
            }
            KitGridRow(weights, cells, bold = true)
        }
    }
}

private val WRAP_WEIGHTS = listOf(1.2f, 2f, 1.4f, 1.4f, 1.1f, 1.4f)

/** Every active asset grouped by source: what returns to vendors, what goes to storage. */
@Composable
private fun WrapReport(data: Rec?) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ReportsModel.wrapGroups(data).forEach { (source, items) ->
            SectionCard(title = "${tEnum(source)} (${items.size})", flush = true) {
                KitHeader(
                    listOf(
                        t("csync_rpt_col_asset"),
                        t("csync_field_description"),
                        t("csync_field_character"),
                        t("csync_field_vendor"),
                        t("csync_field_status"),
                        t("csync_field_location"),
                    ),
                    WRAP_WEIGHTS,
                )
                items.forEach { c ->
                    KitGridRow(
                        WRAP_WEIGHTS,
                        listOf(
                            { KitText(c.str("asset_number"), mono = true, strong = true, maxLines = 1) },
                            { KitText(c.str("name") + ReportsModel.qtySuffix(c.long("quantity"))) },
                            { KitText(c.rec("character")?.str("name").orEmpty()) },
                            { KitText(c.rec("vendor")?.str("name").orEmpty()) },
                            { StatusBadge(c.str("status"), tEnum(c.str("status"))) },
                            { KitText(c.str("location")) },
                        ),
                        bold = true,
                    )
                }
            }
        }
    }
}
