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

/** Puts [load] in reach of every person shown below it. */
@Composable
fun ProvideCashFaces(load: suspend (String) -> ImageBitmap?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCashFaces provides load, content = content)
}

/**
 * One person: their photo, their name, and optionally what they are here as.
 *
 * [userId] may be blank — a float can outlive the crew member who held it —
 * in which case the avatar falls back to initials from the name, which is what
 * [ZillitAvatar] does with no image anyway.
 */
@Composable
fun CashPerson(
    name: String,
    userId: String?,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    size: Dp = PERSON_AVATAR,
) {
    val shown = name.ifBlank { "Unknown" }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = shown, image = rememberFace(userId), size = size)
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
 * one column in these tables an accountant scans by recognition.
 */
fun <T> personColumn(
    header: String,
    width: ColumnWidth = ColumnWidth.Weight(PERSON_WEIGHT),
    userId: (T) -> String?,
    name: (T) -> String,
): TableColumn<T> = TableColumn(
    header = header,
    width = width,
    cell = { row -> CashPerson(name = name(row), userId = userId(row), modifier = Modifier.fillMaxWidth()) },
)
