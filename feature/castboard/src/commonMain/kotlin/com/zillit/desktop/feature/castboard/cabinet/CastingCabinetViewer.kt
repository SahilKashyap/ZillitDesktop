package com.zillit.desktop.feature.castboard.cabinet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.cabinet.CabinetEntry
import com.zillit.desktop.core.cabinet.CabinetViewer
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.castboard.data.CastingRepositoryImpl
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.BoardUnitKind
import com.zillit.desktop.feature.castboard.domain.CastingMedia
import com.zillit.desktop.feature.castboard.domain.CastingRepository
import com.zillit.desktop.feature.castboard.ui.CastingEvent
import com.zillit.desktop.feature.castboard.ui.CastingScreen
import com.zillit.desktop.feature.castboard.ui.CastingViewModel
import kotlin.time.Clock

/**
 * The four casting and wardrobe rows of the File Cabinet.
 *
 * The web mounts `MainCasting`, `ExtraCasting`, `WardrobeMainPage` and
 * `WardrobeBackgroundPage` in a modal with `postingRight="false"` and the
 * tool's cabinet flag. Here the same board — [CastingScreen] over
 * [CastingViewModel] — is mounted read-only, on a repository that keeps the
 * deleted rows the live board hides ([CastingRepositoryImpl]'s `fileCabinet`).
 *
 * One row is one list: "Casting (Main Cast)" is not the casting board with its
 * two tabs, it is the main unit alone, which is why each viewer names the unit
 * it serves.
 */
class CastingCabinetViewer(
    override val identifier: String,
    private val board: BoardTool,
    private val unit: BoardUnitKind,
    private val repository: CastingRepository,
    /**
     * The viewer's tool rights, which is where a unit's id comes from — every
     * board endpoint takes the unit id, not the tool identifier. Null when the
     * rights could not be read.
     */
    private val permissions: suspend () -> ProjectPermissions?,
    private val loadPhoto: (suspend (CastingMedia) -> ImageBitmap?)? = null,
) : CabinetViewer {

    @Composable
    override fun Content(entry: CabinetEntry?, modifier: Modifier) {
        val access by produceState<Access>(Access.Pending) {
            value = permissions()?.let { Access.Ready(it) } ?: Access.Failed
        }
        when (val answer = access) {
            Access.Pending -> Centred(modifier) {
                ZillitText(
                    text = str(S.ah_loading),
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                )
            }

            Access.Failed -> Centred(modifier) {
                ZillitEmptyState(title = str(S.something_went_wrong), icon = ZillitIcons.Shield)
            }

            is Access.Ready -> Board(answer.permissions, modifier)
        }
    }

    @Composable
    private fun Board(permissions: ProjectPermissions, modifier: Modifier) {
        // Never cleared: a Jetpack ViewModel cannot be from here, but with no
        // socket and no badge ledger wired it holds nothing past a load.
        val viewModel = remember(permissions) {
            CastingViewModel(
                repository = repository,
                nowMillis = { Clock.System.now().toEpochMilliseconds() },
                board = board,
                permissions = { permissions },
                onlyUnit = unit.identifier,
            )
        }
        val state by viewModel.state.collectAsState()
        LaunchedEffect(viewModel) { viewModel.onEvent(CastingEvent.Load) }
        CastingScreen(
            state = state,
            onEvent = viewModel::onEvent,
            modifier = modifier,
            board = board,
            loadPhoto = loadPhoto,
            readOnly = true,
        )
    }

    private sealed interface Access {
        data object Pending : Access
        data object Failed : Access
        data class Ready(val permissions: ProjectPermissions) : Access
    }
}

@Composable
private fun Centred(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * The four viewers, one per cabinet row, over one cabinet-mode repository per board.
 *
 * The cipher and identity lambdas are the ones the live boards are built with
 * (`buildCastBoard`): a board's discussion is encrypted like chat, and opening
 * a character's thread from the cabinet reads it the same way.
 */
fun castingCabinetViewers(
    apiClient: ApiClient,
    config: AppConfig,
    permissions: suspend () -> ProjectPermissions?,
    encrypt: (String) -> String?,
    decrypt: (String) -> String?,
    myUserId: () -> String?,
    loadPhoto: (suspend (CastingMedia) -> ImageBitmap?)? = null,
): List<CabinetViewer> = listOf(
    BoardTool.Casting to CASTING_ROWS,
    BoardTool.Wardrobe to WARDROBE_ROWS,
).flatMap { (board, rows) ->
    val repository = CastingRepositoryImpl(
        apiClient,
        config,
        board,
        encrypt = encrypt,
        decrypt = decrypt,
        myUserId = myUserId,
        fileCabinet = true,
    )
    // The rows follow the board's own unit order: main, then background.
    rows.zip(board.units).map { (identifier, unit) ->
        CastingCabinetViewer(identifier, board, unit, repository, permissions, loadPhoto)
    }
}

/** The web's `commonUnits[].identifier`s, main then background. */
private val CASTING_ROWS = listOf("casting_main", "casting_background")
private val WARDROBE_ROWS = listOf("wardrobe_main", "wardrobe_background")
