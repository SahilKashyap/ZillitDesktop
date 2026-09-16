package com.zillit.desktop.feature.cashexpenses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.cashexpenses.domain.CashPeople

/**
 * Faces for the cash tool — the crew photo beside every name.
 *
 * Cash is a tool about people: whose float it is, who submitted the receipts,
 * who is being asked to sign them off. A column of bare names makes an
 * accountant read every row; a face is recognised before the name is read,
 * which is why the web puts a `UserAvatar` on every claimant on every one of
 * these screens.
 *
 * The loader is a composition local rather than a parameter because the
 * people appear eight or nine levels down — inside table cells built by
 * `TableColumn` lambdas — and threading a suspend function through every page
 * signature to reach them would be the whole diff.
 */
val LocalCashFaces: ProvidableCompositionLocal<suspend (String) -> ImageBitmap?> =
    staticCompositionLocalOf { { _: String -> null } }

/**
 * Who each user id is — the name beside every face.
 *
 * A local for the same reason as [LocalCashFaces]: the names are drawn in the
 * same table cells. Empty by default, so a screen composed without a crew list
 * says "Unknown" rather than printing ids.
 */
val LocalCashPeople: ProvidableCompositionLocal<CashPeople> = staticCompositionLocalOf { CashPeople() }

/** Puts [load] and [people] in reach of every person shown below them. */
@Composable
fun ProvideCashFaces(
    load: suspend (String) -> ImageBitmap?,
    people: CashPeople = CashPeople(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalCashFaces provides load, LocalCashPeople provides people, content = content)
}

/**
 * One person: their photo, their name, and optionally what they are here as.
 *
 * The name is looked up from [userId] in [LocalCashPeople], as the web does on
 * every cash screen; [recordedName] is what the row itself carried, used only
 * when the crew list does not know the id. Never the id itself — see
 * [CashPeople]. With no [userId] (a float can outlive the crew member who held
 * it) the avatar falls back to initials, which is what [ZillitAvatar] does with
 * no image anyway.
 */
@Composable
fun CashPerson(
    userId: String?,
    modifier: Modifier = Modifier,
    recordedName: String? = null,
    secondary: String? = null,
    size: Dp = PERSON_AVATAR,
) {
    val shown = LocalCashPeople.current.nameOf(userId, recordedName)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = shown, image = rememberFace(userId), userId = userId, size = size)
        if (secondary.isNullOrBlank()) {
            ZillitText(
                text = shown,
                style = ZillitTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Column {
                ZillitText(
                    text = shown,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ZillitText(
                    text = secondary,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The crew photo for [userId], or null while it loads and for anyone without one.
 *
 * Keyed on the id so a recycled row re-reads rather than showing the previous
 * occupant's face; the host's loader caches, so a table of twenty rows costs
 * twenty cache reads, not twenty fetches.
 */
@Composable
fun rememberFace(userId: String?): ImageBitmap? {
    val load = LocalCashFaces.current
    return produceState<ImageBitmap?>(initialValue = null, userId) {
        value = userId?.takeIf { it.isNotBlank() }?.let { load(it) }
    }.value
}

private val PERSON_AVATAR = 26.dp
private const val PERSON_WEIGHT = 1.4f

/**
 * A table column of people — the face beside the name, in a cell.
 *
 * Its own helper rather than `textColumn`, because a column of names is the
 * one column in these tables an accountant scans by recognition. The name
 * comes from [userId]; [recordedName] is the row's own, for someone the crew
 * list does not know — see [CashPerson].
 */
fun <T> personColumn(
    header: String,
    width: ColumnWidth = ColumnWidth.Weight(PERSON_WEIGHT),
    userId: (T) -> String?,
    recordedName: (T) -> String? = { null },
): TableColumn<T> = TableColumn(
    header = header,
    width = width,
    cell = { row ->
        CashPerson(userId = userId(row), recordedName = recordedName(row), modifier = Modifier.fillMaxWidth())
    },
)
