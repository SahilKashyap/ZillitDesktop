package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costumesetsync.domain.BudgetAccount
import com.zillit.desktop.feature.costumesetsync.domain.BudgetForm
import com.zillit.desktop.feature.costumesetsync.domain.BudgetModel
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.humanize
import com.zillit.desktop.feature.costumesetsync.domain.todayParam
import com.zillit.desktop.feature.costumesetsync.ui.DateInput
import com.zillit.desktop.feature.costumesetsync.ui.EnumInput
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PickInput
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch

private const val SUGGESTIONS = 6

/** What the add / edit dialog needs besides the line itself. */
internal class BudgetLineContext(
    val currency: String,
    val cats: List<String>,
    val knownAccounts: List<BudgetAccount>,
    val scenes: List<Rec>,
    val characters: List<Rec>,
    val vendors: List<Rec>,
)

/**
 * Add or edit one budget line (the web's `BudgetLineModal`).
 *
 * Amt × X × Rate works the amount out once Amt and Rate are both filled in, as a printed budget does;
 * otherwise the amount is typed. Picking a known account code fills its name. After an add the account,
 * payee, scene, character, category and currency stay for the next line. [remembered] carries the form
 * across opens, so an Add starts from whatever the form last held.
 */
@Composable
internal fun BudgetLineDialog(
    open: Boolean,
    editing: Rec?,
    context: BudgetLineContext,
    remembered: BudgetFormHolder,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    val today = todayParam(ctx.now())
    var f by remember { mutableStateOf(remembered.form ?: BudgetModel.blankForm(context.currency, today)) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(open, editing?.id) {
        if (!open) return@LaunchedEffect
        f = if (editing != null) BudgetModel.toForm(editing, context.currency) else BudgetModel.reopenForAdd(remembered.form ?: f, context.currency)
    }
    val accounts = remember(context.knownAccounts) {
        (BudgetModel.WARDROBE_ACCOUNTS + context.knownAccounts).distinctBy { it.code }.filter { it.code.isNotEmpty() }
    }
    val subtotal = BudgetModel.subtotalOf(f)
    val amount = BudgetModel.amountOf(f)
    val ownCategory = f.category == BudgetModel.OTHER || f.category !in context.cats
    val ready = !(amount != null && amount < 0) && !saving

    fun setCode(code: String) {
        val hit = accounts.firstOrNull { it.code == code.trim() }
        val oldName = accounts.firstOrNull { it.code == f.accountCode.trim() }?.name
        f = f.copy(accountCode = code, accountName = if (hit != null && (f.accountName.isEmpty() || f.accountName == oldName)) hit.name else f.accountName)
    }

    FormDialog(
        open = open,
        title = t(if (editing != null) "csync_budget_edit_line" else "csync_budget_add_line"),
        onDismiss = onClose,
        confirmLabel = t(if (editing != null) "csync_save" else "csync_add"),
        onConfirm = {
            saving = true
            scope.launch {
                val body = BudgetModel.toExpenseBody(f, context.currency)
                val saved = ctx.write { if (editing != null) ctx.api.patch("/expenses/${editing.id}", body) else ctx.api.post("/expenses", body) }
                saving = false
                if (saved != null) {
                    if (editing == null) f = BudgetModel.formAfterAdd(f, context.currency, today)
                    remembered.form = f
                    onSaved()
                    onClose()
                }
            }
        },
        confirmEnabled = ready,
        busy = saving,
        width = DIALOG_WIDTH,
    ) {
        // `.csync-form-grid`: two equal columns across the 900-wide body.
        val half = Modifier.width(HALF)
        FormGrid {
            TextInput(f.accountCode, { setCode(it) }, t("csync_budget_account_code"), half, help = t("csync_budget_account_code_hint"))
            TextInput(f.accountName, { f = f.copy(accountName = it) }, t("csync_budget_account_name"), half)
            AccountSuggestions(f.accountCode, accounts) { setCode(it.code); f = f.copy(accountCode = it.code, accountName = it.name) }
            TextInput(f.description, { f = f.copy(description = it) }, t("csync_field_description"), Modifier.width(FULL))
            CharacterSelect(f.characterId, { f = f.copy(characterId = it) }, t("csync_field_character"), half, rows = context.characters)
            TextInput(f.payee, { f = f.copy(payee = it) }, t("csync_budget_pay_to"), half, help = t("csync_budget_pay_to_hint"))
            // The category picker, and under it (same field) the box that names a production's own.
            Column(half, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                EnumInput(if (ownCategory) BudgetModel.OTHER else f.category, context.cats, { f = f.copy(category = it) }, t("csync_field_category"), Modifier.fillMaxWidth())
                if (ownCategory) {
                    TextInput(
                        if (f.category == BudgetModel.OTHER) "" else humanize(f.category),
                        { f = f.copy(category = it.ifBlank { BudgetModel.OTHER }) },
                        t("csync_budget_new_category"),
                        Modifier.fillMaxWidth(),
                        help = t("csync_budget_own_category_hint"),
                    )
                }
            }
        }
        // `.csync-budget-calc`: Amt 90 · Unit 110 · X 70 · Rate 120 · Currency 110, then the subtotal / amount taking the rest.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            TextInput(f.quantity, { f = f.copy(quantity = it) }, t("csync_budget_amt"), Modifier.width(AMT_W), number = true)
            TextInput(f.unit, { f = f.copy(unit = it) }, t("csync_budget_unit"), Modifier.width(UNIT_W), help = BudgetModel.BUDGET_UNITS.take(UNIT_HINTS).joinToString(" · "))
            TextInput(f.multiplier, { f = f.copy(multiplier = it) }, "X", Modifier.width(X_W), number = true)
            TextInput(f.rate, { f = f.copy(rate = it) }, t("csync_budget_rate"), Modifier.width(RATE_W), number = true)
            val currencies = (listOf("" to t("csync_budget_currency_none")) + (listOfNotNull(context.currency.ifEmpty { null }) + BudgetModel.CURRENCIES + listOfNotNull(f.currency.ifEmpty { null })).distinct().map { it to it })
            PickInput(f.currency, currencies, { f = f.copy(currency = it) }, t("csync_budget_currency"), Modifier.width(UNIT_W), "")
            if (subtotal != null) {
                Column(Modifier.width(REST_W)) {
                    ZillitText(t("csync_budget_subtotal"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
                    ZillitText(BudgetModel.fmtAmount(subtotal, f.currency), Modifier.padding(vertical = 5.dp), style = ZillitTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                    MutedText(t("csync_budget_subtotal_hint"), maxLines = 2)
                }
            } else {
                TextInput(
                    f.amount,
                    { f = f.copy(amount = it) },
                    t("csync_budget_amount") + if (f.currency.isNotEmpty()) " (${f.currency})" else "",
                    Modifier.width(REST_W),
                    number = true,
                    help = t("csync_budget_amount_hint"),
                )
            }
        }
        FormGrid {
            SceneSelect(f.sceneId, { f = f.copy(sceneId = it) }, t("csync_field_scene"), half, rows = context.scenes)
            VendorSelect(f.vendorId, { f = f.copy(vendorId = it) }, t("csync_field_vendor"), half, rows = context.vendors)
            DateInput(f.date, { f = f.copy(date = it) }, t("csync_field_date"), half)
        }
    }
}

private val DIALOG_WIDTH = 900.dp
private val HALF = 420.dp
private val FULL = 852.dp
private val AMT_W = 90.dp
private val UNIT_W = 110.dp
private val X_W = 70.dp
private val RATE_W = 120.dp
private val REST_W = 240.dp
private const val UNIT_HINTS = 6

/** The form kept across opens: an Add starts from what it last held. */
internal class BudgetFormHolder {
    var form: BudgetForm? = null
}

/** Accounts whose code or name matches what is typed, offered as one-click fills. */
@Composable
private fun AccountSuggestions(typed: String, accounts: List<BudgetAccount>, onPick: (BudgetAccount) -> Unit) {
    val needle = typed.trim().lowercase()
    if (needle.isEmpty() || accounts.any { it.code == typed.trim() }) return
    val matches = accounts.filter { it.code.lowercase().contains(needle) || it.name.lowercase().contains(needle) }.take(SUGGESTIONS)
    if (matches.isEmpty()) return
    FlowRow(FormWide, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        matches.forEach { ZillitChoiceChip("${it.code} ${it.name}", false, onClick = { onPick(it) }) }
    }
}
