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
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** `PAY_FREQUENCIES`. */
internal val PAY_FREQUENCIES = listOf(
    "weekly" to "Per Week",
    "daily" to "Per Day",
    "shoot" to "Per Shoot Day",
    "non_shoot" to "Per Non-Shoot Day",
    "all" to "All Days",
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
            Field("Payroll bureau") {
                BuilderInput(
                    value = form.text("bureau"),
                    onValueChange = { ops.set("bureau", it) },
                    placeholder = "e.g. Sargent-Disc",
                )
                HintText(
                    "No bureaus are set up for this project yet. What you enter here becomes the project's first " +
                        "bureau.",
                    Modifier.padding(top = 6.dp),
                )
            }
        }
    }
    CardBlock(title = "Payroll Bureau / Processing Method") {
        when {
            !settings.loaded -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitSpinner(size = 14.dp, color = bp.cta)
                ZillitText(text = "Loading bureaus…", style = DmType.sans(12.5.sp), color = bp.muted)
            }
            bureaus.isEmpty() -> EmptyNote(
                "No payroll bureaus configured in Production Setup yet. Add them under Production Setup → Payroll " +
                    "Bureau to surface them here.",
            )
            else -> BuilderGrid(columns = 2) {
                cell {
                    Field("Payroll Bureau") {
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
                            placeholder = "Select payroll bureau…",
                        )
                    }
                }
            }
        }
    }
    CardBlock(title = "Export & Sync Settings") {
        BuilderGrid(columns = 2) {
            cell {
                Field("First Pay Period Start") {
                    IsoDateInput(value = form.text("firstPayPeriod"), onChange = { ops.set("firstPayPeriod", it) })
                }
            }
            cell {
                Field("Pay Frequency") {
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
