package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** `PAY_FREQUENCIES`. */
internal val PAY_FREQUENCIES get() = listOf(
    "weekly" to str(S.desktop_dm_freq_per_week),
    "daily" to str(S.desktop_dm_freq_per_day),
    "shoot" to str(S.desktop_dm_freq_per_shoot_day),
    "non_shoot" to str(S.desktop_dm_freq_per_non_shoot_day),
    "all" to str(S.desktop_dm_all_days),
)

/**
 * Payroll Start Form (`Step9Payroll.jsx`), with the page's free-text bureau
 * while the project has none: the bureau, the first pay period and the pay
 * frequency.
 */
@Suppress("LongMethod")
@Composable
internal fun PayrollEditor(state: DealMemoUiState, builder: BuilderState, ops: FormOps) {
    val form = builder.form
    val settings = state.projectSettings
    val bureaus = settings.view.payrollBureaus.filter {
        DocRead.text(it, "id") != null || DocRead.text(it, "title") != null
    }
    FirstPayPeriodEffect(builder, ops)
    LaunchedEffect(settings.loaded, bureaus.size) {
        // `pickDefaultBureau`: never over a chosen bureau; the first one's title otherwise.
        if (settings.loaded && form.text("bureau").isEmpty()) {
            bureaus.firstOrNull()?.let { DocRead.text(it, "title") }?.let { ops.set("bureau", it) }
        }
    }
    if (settings.loaded && settings.view.payrollBureaus.isEmpty()) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Field(str(S.desktop_dm_payroll_bureau_lower)) {
                BuilderInput(
                    value = form.text("bureau"),
                    onValueChange = { ops.set("bureau", it) },
                    placeholder = str(S.desktop_dm_e_g_sargent_disc),
                )
                HintText(
                    str(S.desktop_dm_no_bureaus_are_set_up_for_this),
                    Modifier.padding(top = 6.dp),
                )
            }
        }
    }
    CardBlock(title = str(S.dm_pay_card_bureau)) {
        when {
            !settings.loaded -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitSpinner(size = 14.dp, color = bp.cta)
                ZillitText(text = str(S.desktop_dm_loading_bureaus), style = DmType.sans(12.5.sp), color = bp.muted)
            }
            bureaus.isEmpty() -> EmptyNote(
                str(S.desktop_dm_no_payroll_bureaus_configured_in_production_setup),
            )
            else -> BuilderGrid(columns = 2) {
                cell {
                    Field(str(S.desktop_payroll_bureau)) {
                        val options = bureaus.map { row ->
                            val title = DocRead.text(row, "title").orEmpty()
                            val description = DocRead.text(row, "description")
                            PickOption(
                                key = DocRead.text(row, "id") ?: title,
                                label = title,
                                sub = description,
                                search = listOfNotNull(title, description).joinToString(" "),
                            )
                        }
                        RichSelect(
                            options = options,
                            selectedKey = options.firstOrNull { it.label == form.text("bureau") }?.key,
                            onPick = { key ->
                                ops.set("bureau", options.firstOrNull { it.key == key }?.label.orEmpty())
                            },
                            placeholder = str(S.desktop_dm_select_payroll_bureau),
                        )
                    }
                }
            }
        }
    }
    CardBlock(title = str(S.dm_pay_card_export_sync)) {
        BuilderGrid(columns = 2) {
            cell {
                Field(str(S.dm_pay_first_period)) {
                    IsoDateInput(value = form.text("firstPayPeriod"), onChange = { ops.set("firstPayPeriod", it) })
                }
            }
            cell {
                Field(str(S.dm_allow_basis)) {
                    NativeSelect(
                        value = form.text("payFrequency"),
                        options = PAY_FREQUENCIES.map { PickOption(it.first, it.second) },
                        onPick = { ops.set("payFrequency", it) },
                    )
                }
            }
        }
    }
}

/**
 * The first pay period follows the start — prep's when the schedule is on —
 * moved back to its Monday for a weekly frequency; recomputed whenever those change.
 */
@Composable
private fun FirstPayPeriodEffect(builder: BuilderState, ops: FormOps) {
    val form = builder.form
    val frequency = form.text("payFrequency")
    val base = if (form.flag("schedOn") && form.text("schedPrepStart").isNotEmpty()) {
        form.text("schedPrepStart")
    } else {
        form.text("dealStart")
    }
    LaunchedEffect(frequency, base) {
        val date = runCatching { LocalDate.parse(base) }.getOrNull() ?: return@LaunchedEffect
        val first = if (frequency == "weekly") {
            date.minus((date.dayOfWeek.ordinal - DayOfWeek.MONDAY.ordinal).toLong(), DateTimeUnit.DAY)
        } else {
            date
        }
        if (first.toString() != form.text("firstPayPeriod")) ops.set("firstPayPeriod", first.toString())
    }
}
