package com.zillit.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType
import com.zillit.desktop.feature.calls.ui.CallEvent
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.crewlist.ui.CrewListSlots
import com.zillit.desktop.feature.crewlist.ui.CrewListToolProvider
import com.zillit.desktop.feature.crewlist.ui.CrewListViewModel
import com.zillit.desktop.feature.crewlist.ui.dialogs.CrewContactActions
import com.zillit.desktop.feature.externalusers.ui.ExternalUserFormDialog
import com.zillit.desktop.feature.externalusers.ui.ExternalUsersEffect
import com.zillit.desktop.feature.externalusers.ui.ExternalUsersEvent
import com.zillit.desktop.feature.externalusers.ui.ExternalUsersViewModel
import kotlinx.coroutines.delay

/**
 * The Crew List tool with everything its screen borrows from the app: the
 * browser canvas, faces, the External Users form, and the profile drawer's
 * call, chat and mail — each the same act the app's own tool performs.
 */
internal fun AppGraph.Ready.crewListProvider(
    viewModel: CrewListViewModel,
    viewModels: AppViewModels,
    onOpenWidget: () -> Unit,
): CrewListToolProvider {
    val permissions = { viewModels.home?.state?.value?.permissions ?: ProjectPermissions.Empty }
    // Its own form model, so a contact half-added here never shows up in the
    // External Users window, and the other way round.
    val externalUsers by lazy { buildExternalUsers(permissions) }
    val faces = crewFaceLoader(this)
    return CrewListToolProvider(
        viewModel = viewModel,
        onOpenWidget = onOpenWidget,
        slots = { navigator ->
            CrewListSlots(
                canvas = crewListCanvas,
                externalUserForm = { onFinished -> CrewExternalUserForm(externalUsers, onFinished) },
                faces = faces,
                contact = crewContactActions(viewModels, navigator),
            )
        },
    )
}

/** Call, chat and mail from a crew member's profile — the drawer's four buttons. */
private fun AppGraph.Ready.crewContactActions(viewModels: AppViewModels, navigator: WindowNavigator) =
    CrewContactActions(
        call = viewModels.calls?.let { calls ->
            { member, video ->
                val device = projectContext?.context?.value?.user(member.userId)?.deviceId.orEmpty()
                calls.onEvent(
                    CallEvent.Place(
                        chatRoomId = "",
                        receiverDeviceId = device,
                        mode = CallMode.Private,
                        type = if (video) CallType.Video else CallType.Audio,
                        displayName = member.fullName,
                        receiverUserId = member.userId,
                    ),
                )
            }
        },
        chat = viewModels.chat?.let { chat ->
            { member ->
                chat.onEvent(ChatEvent.OpenThread(CrewContact(member.userId, member.fullName)))
                navigator.openInNewWindow(WorkspaceRoute.Tool(CHAT_ROUTE))
            }
        },
        email = viewModels.email?.let { mail ->
            { address ->
                // Queued on the mailbox, so it is raised whether the mail
                // window is already open or opens now.
                mail.composeRequests.post(address)
                navigator.openInNewWindow(WorkspaceRoute.Tool(EMAIL_ROUTE))
            }
        },
    )

/**
 * The External Users form, opened in place. Reports back once it closes: with
 * the form's success line when a contact was added, null when it was cancelled
 * or refused.
 */
@Composable
private fun CrewExternalUserForm(viewModel: ExternalUsersViewModel, onFinished: (String?) -> Unit) {
    val state by viewModel.state.collectAsState()
    var saved by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is ExternalUsersEffect.Notice && opened) saved = effect.text
        }
    }
    LaunchedEffect(viewModel) {
        // Rights and departments for this production, then a blank draft.
        viewModel.start()
        viewModel.onEvent(ExternalUsersEvent.New)
        opened = true
    }
    LaunchedEffect(opened, state.editing, state.isSaving) {
        if (!opened) return@LaunchedEffect
        if (state.editing != null) {
            submitting = state.isSaving
            return@LaunchedEffect
        }
        // The form closed. A save in flight when it did is an added contact; the
        // success line is an effect and may land a beat after the state does.
        if (!submitting) delay(NOTICE_GRACE_MILLIS)
        val added = submitting || saved?.contains("added", ignoreCase = true) == true
        onFinished(if (added) saved ?: str(S.desktop_eu_user_added) else null)
    }
    ExternalUserFormDialog(state, viewModel::onEvent)
}

private const val CHAT_ROUTE = "/cnc"
private const val EMAIL_ROUTE = "/email"
private const val NOTICE_GRACE_MILLIS = 250L
