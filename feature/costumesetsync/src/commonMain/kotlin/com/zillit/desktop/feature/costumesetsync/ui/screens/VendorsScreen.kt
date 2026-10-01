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
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.BudgetModel
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.fmtDate
import com.zillit.desktop.feature.costumesetsync.domain.fmtMoney
import com.zillit.desktop.feature.costumesetsync.domain.matches
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.CostumeRow
import com.zillit.desktop.feature.costumesetsync.ui.DateInput
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FieldRow
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch

private val OUT = setOf("BOOKED", "PICKED_UP", "OVERDUE")
private const val DAY_MS = 86_400_000L
private val SEARCH_WIDTH = 420.dp
private val CARD_WIDTH = 330.dp
private val ROW_WEIGHTS = listOf(2.2f, 1.4f, 1f, 1.4f, 1f, 1f, 1.6f)
private val ROW_WEIGHTS_NO_RATE = ROW_WEIGHTS.filterIndexed { i, _ -> i != RATE_AT }
private const val RATE_AT = 4

/** Vendors and rentals read together. */
private class VendorData(val vendors: List<Rec>, val rentals: List<Rec>)

/**
 * Vendors & Rentals: who the production rents from, what is out, and when it is due back. Rentals are
 * recorded here, moved to Picked up / Returned, and chased with "Send return reminders". Vendors are shown
 * as cards. (The web page has no external-contacts tab; contacts are picked in the send-request dialog.)
 */
@Composable
fun VendorsScreen() {
    val ctx = LocalSync.current
    val data = rememberResource {
        val vendors = api.get("/vendors").mapRows()
        val rentals = api.get("/rentals").mapRows()
        when {
            vendors is ZillitResult.Failure -> vendors
            else -> ZillitResult.Success(
                VendorData(
                    (vendors as ZillitResult.Success).data,
                    (rentals as? ZillitResult.Success)?.data.orEmpty(),
                ),
            )
        }
    }
    SocketRefresh(SyncEvents.Vendor + SyncEvents.Rental) { data.reload(silent = true) }
    var tab by remember { mutableStateOf("rentals") }
    var q by remember { mutableStateOf("") }
    var vendorOpen by remember { mutableStateOf(false) }
    var rentalOpen by remember { mutableStateOf(false) }
    var remindOpen by remember { mutableStateOf(false) }
    val reload = { data.reload(silent = true) }

    Await(data) { book ->
        PageHead(
            title = t("csync_vendors_rentals"),
            sub = t("csync_vendors_rentals_sub"),
            actions = {
                if (ctx.canPost) {
                    if (tab == "rentals") {
                        ZillitButton(t("csync_send_return_reminders"), onClick = { remindOpen = true }, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Bell)
                        ZillitButton(t("csync_rental"), onClick = { rentalOpen = true }, leadingIcon = ZillitIcons.Add)
                    } else {
                        ZillitButton(t("csync_vendor"), onClick = { vendorOpen = true }, leadingIcon = ZillitIcons.Add)
                    }
                }
            },
        )
        KitTabs(listOf("rentals" to "${t("csync_rentals")} (${book.rentals.size})", "vendors" to "${t("csync_vendors")} (${book.vendors.size})"), tab) { tab = it }
        Column(Modifier.padding(top = ZillitTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            if ((if (tab == "rentals") book.rentals else book.vendors).isNotEmpty()) {
                ZillitSearchField(q, { q = it }, Modifier.width(SEARCH_WIDTH), placeholder = t(if (tab == "rentals") "csync_rentals_search" else "csync_vendors_search"))
            }
            if (tab == "rentals") RentalsTab(ctx, book.rentals, q, reload) else VendorsTab(book.vendors, q)
        }
        VendorDialog(vendorOpen, { vendorOpen = false }, reload)
        RentalDialog(rentalOpen, book.vendors, { rentalOpen = false }, reload)
        ReminderDialog(ctx, remindOpen, book.rentals) { remindOpen = false }
    }
}

@Composable
private fun RentalsTab(ctx: SyncCtx, rentals: List<Rec>, q: String, reload: () -> Unit) {
    val shown = rentals.filter { matches(q, it.rec("costume")?.str("asset_number"), it.rec("costume")?.str("name"), it.rec("vendor")?.str("name"), tEnum(it.str("status"))) }
    SectionCard(flush = true) {
        if (rentals.isEmpty()) {
            EmptyState(t("csync_rentals_none"))
            return@SectionCard
        }
        if (shown.isEmpty()) {
            EmptyState(t("csync_rentals_no_match"))
            return@SectionCard
        }
        val weights = if (ctx.isFinance) ROW_WEIGHTS else ROW_WEIGHTS_NO_RATE
        KitHeader(
            listOf(t("csync_field_costume"), t("csync_field_vendor"), t("csync_pickup"), t("csync_return")) +
                (if (ctx.isFinance) listOf(t("csync_rate_per_day")) else emptyList()) + listOf(t("csync_field_status"), t("csync_actions")),
            weights,
        )
        shown.forEach { r ->
            val cells = buildList<@Composable () -> Unit> {
                add {
                    ZillitButton(
                        (r.rec("costume")?.str("asset_number").orEmpty() + " " + r.rec("costume")?.str("name").orEmpty()).trim(),
                        onClick = { ctx.nav.go("costumes/${r.rec("costume")?.id.orEmpty()}") },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
                add { KitText(r.rec("vendor")?.str("name").orEmpty()) }
                add { KitText(fmtDate(r.long("pickup_date")), maxLines = 1) }
                add { ReturnCell(r) }
                if (ctx.isFinance) add { KitText(if (r.has("rate_per_day")) fmtMoney(r.double("rate_per_day"), ctx.currency) else "—", maxLines = 1) }
                add { StatusBadge(r.str("status"), tEnum(r.str("status"))) }
                add { RentalActions(ctx, r, reload) }
            }
            KitGridRow(weights, cells, bold = true)
        }
    }
}

@Composable
private fun ReturnCell(r: Rec) {
    Column {
        KitText(fmtDate(r.long("return_date")), maxLines = 1)
        // The service computes these — an overdue rental is the one thing here somebody has to act on today.
        if (r.bool("is_overdue")) StatusBadge("OVERDUE", t("csync_rental_overdue")) else if (r.bool("due_soon")) StatusBadge("DUE", t("csync_rental_due_soon"))
    }
}

@Composable
private fun RentalActions(ctx: SyncCtx, r: Rec, reload: () -> Unit) {
    if (!ctx.canPost || r.str("status") == "RETURNED") return
    fun move(status: String) = ctx.launchWrite({ ctx.api.patch("/rentals/${r.id}", body("status" to status)) }, onDone = { reload() })
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        if (r.str("status") == "BOOKED") ZillitButton(t("csync_picked_up"), onClick = { move("PICKED_UP") }, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
        ZillitButton(t("csync_returned"), onClick = { move("RETURNED") }, size = ButtonSize.Small)
    }
}

@Composable
private fun VendorsTab(vendors: List<Rec>, q: String) {
    val shown = vendors.filter { matches(q, it.str("name"), it.str("contact_name"), it.str("phone"), it.str("email"), it.str("address")) }
    when {
        vendors.isEmpty() -> SectionCard { EmptyState(t("csync_vendors_none")) }
        shown.isEmpty() -> SectionCard { EmptyState(t("csync_vendors_no_match")) }
        else -> FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            shown.forEach { v ->
                SectionCard(title = v.str("name"), modifier = Modifier.width(CARD_WIDTH)) {
                    FieldRow(t("csync_field_contact"), v.str("contact_name"))
                    FieldRow(t("csync_field_phone"), v.str("phone"))
                    FieldRow(t("csync_field_email"), v.str("email"))
                    FieldRow(t("csync_field_address"), v.str("address"))
                    FieldRow(t("csync_items"), t("csync_vendor_counts", "c" to (v.rec("counts")?.long("costumes") ?: 0L), "r" to (v.rec("counts")?.long("rentals") ?: 0L)))
                    if (v.str("notes").isNotBlank()) MutedText(v.str("notes"), maxLines = 4)
                }
            }
        }
    }
}

@Composable
private fun VendorDialog(open: Boolean, onClose: () -> Unit, reload: () -> Unit) {
    val ctx = LocalSync.current
    var name by remember(open) { mutableStateOf("") }
    var contact by remember(open) { mutableStateOf("") }
    var phone by remember(open) { mutableStateOf("") }
    var email by remember(open) { mutableStateOf("") }
    var address by remember(open) { mutableStateOf("") }
    var notes by remember(open) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    FormDialog(
        open = open,
        title = t("csync_new_vendor"),
        onDismiss = onClose,
        confirmLabel = t("csync_add"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val done = ctx.write {
                    ctx.api.post("/vendors", body("name" to name.trim(), "contact_name" to contact, "phone" to phone, "email" to email, "address" to address, "notes" to notes))
                }
                saving = false
                if (done != null) {
                    onClose()
                    reload()
                }
            }
        },
        confirmEnabled = name.isNotBlank(),
        busy = saving,
        width = VENDOR_DIALOG,
    ) {
        FormGrid {
            TextInput(name, { name = it }, t("csync_field_name"), FORM_WIDE)
            TextInput(contact, { contact = it }, t("csync_contact_person"))
            TextInput(phone, { phone = it }, t("csync_field_phone"))
            TextInput(email, { email = it }, t("csync_field_email"))
            TextInput(address, { address = it }, t("csync_field_address"))
            TextInput(notes, { notes = it }, t("csync_field_notes"), FORM_WIDE, multiline = true)
        }
    }
}

private val VENDOR_DIALOG = 640.dp
private val FORM_WIDE = FormWide

@Composable
private fun RentalDialog(open: Boolean, vendors: List<Rec>, onClose: () -> Unit, reload: () -> Unit) {
    val ctx = LocalSync.current
    var costume by remember(open) { mutableStateOf<Rec?>(null) }
    var vendorId by remember(open) { mutableStateOf("") }
    var rate by remember(open) { mutableStateOf("") }
    var pickup by remember(open) { mutableStateOf("") }
    var ret by remember(open) { mutableStateOf("") }
    var notes by remember(open) { mutableStateOf("") }
    var pick by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    FormDialog(
        open = open,
        title = t("csync_record_rental"),
        onDismiss = onClose,
        confirmLabel = t("csync_save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val done = ctx.write {
                    ctx.api.post(
                        "/rentals",
                        // The service takes ISO dates here (it rejects epoch ms on rentals).
                        body("costume_id" to costume?.id.orEmpty(), "vendor_id" to vendorId, "rate_per_day" to (rate.trim().toDoubleOrNull() ?: 0.0), "pickup_date" to pickup, "return_date" to ret, "notes" to notes.trim()),
                    )
                }
                saving = false
                if (done != null) {
                    onClose()
                    reload()
                }
            }
        },
        confirmEnabled = costume != null && vendorId.isNotEmpty() && pickup.isNotEmpty() && ret.isNotEmpty(),
        busy = saving,
        width = VENDOR_DIALOG,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            val c = costume
            if (c != null) {
                CostumeRow(c, onClick = { pick = true }, end = { MutedText(t("csync_change_lower")) })
            } else {
                Row { ZillitButton(t("csync_choose_costume"), onClick = { pick = true }, variant = ButtonVariant.Secondary) }
            }
        }
        FormGrid {
            VendorSelect(vendorId, { vendorId = it }, t("csync_field_vendor"), rows = vendors)
            TextInput(rate, { rate = it }, t("csync_rate_per_day_field") + if (ctx.currency.isNotEmpty()) " (${ctx.currency})" else "", number = true)
            DateInput(pickup, { pickup = it }, t("csync_pickup"))
            DateInput(ret, { ret = it }, t("csync_return_by"))
            TextInput(notes, { notes = it }, t("csync_field_notes"), FORM_WIDE, multiline = true)
        }
    }
    CostumePickerDialog(
        open = pick,
        onClose = { pick = false },
        onPick = { c ->
            costume = c
            if (c.str("vendor_id").isNotEmpty()) vendorId = c.str("vendor_id")
            if (c.double("rental_cost_per_day") > 0) rate = BudgetModel.numText(c.double("rental_cost_per_day"))
        },
    )
}

/** Everything due back within the next day — what a return reminder is about. */
@Composable
private fun ReminderDialog(ctx: SyncCtx, open: Boolean, rentals: List<Rec>, onClose: () -> Unit) {
    val due = rentals.filter { it.str("status") in OUT && it.long("return_date") != 0L && it.long("return_date") <= ctx.now() + DAY_MS }
    val name = ctx.project.name.ifEmpty { t("csync_production") }
    val text = if (due.isEmpty()) {
        t("csync_rentals_nothing_due")
    } else {
        t("csync_rentals_due_back") + "\n" + due.joinToString("\n") {
            "· ${it.rec("costume")?.str("asset_number").orEmpty()} ${it.rec("costume")?.str("name").orEmpty()} (${it.rec("vendor")?.str("name").orEmpty()}) — " + t("csync_due_lower_n", "date" to fmtDate(it.long("return_date")))
        }
    }
    SendRequestDialog(
        open = open,
        onClose = onClose,
        title = t("csync_send_return_reminders"),
        entityType = "RENTAL",
        entityId = due.firstOrNull()?.id,
        defaultTitle = "${t(if (due.isNotEmpty()) "csync_rental_returns_due" else "csync_rentals_still_out")} · $name",
        defaultBody = text,
    )
}
