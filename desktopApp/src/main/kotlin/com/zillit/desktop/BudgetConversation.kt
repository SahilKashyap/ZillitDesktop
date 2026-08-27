package com.zillit.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.budget.data.BudgetRepositoryImpl
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetViewer
import com.zillit.desktop.feature.chat.domain.ChatScope
import com.zillit.desktop.feature.chat.ui.ChatConversation
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatSeams
import com.zillit.desktop.feature.chat.ui.ChatViewModel

/**
 * The conversation beside a budget.
 *
 * The budget tools discuss their documents on the same socket C&C uses, under
 * their own tool name and scoped to a department — so this is the chat tool's
 * own thread, not a second implementation of one. A budget room therefore
 * arrives with replies, attachments, receipts and voice notes already working.
 *
 * One view model per (tool, department): switching department switches
 * conversation, and each keeps its own messages rather than sharing one thread
 * that would show the wrong room for a frame after every click.
 */
@Composable
internal fun BudgetConversationPane(
    ready: AppGraph.Ready,
    document: BudgetDocument?,
    /** Posting rights on this budget — who may open a discussion about it. */
    canStart: Boolean,
    modifier: Modifier = Modifier,
) {
    val tool = when (document?.type) {
        BudgetType.Department -> BudgetViewer.DEPARTMENT_TOOL
        else -> BudgetViewer.MAIN_TOOL
    }
    val departmentId = document?.departmentId.orEmpty()
    // The document is part of the scope: a budget room is created against one
    // (the server refuses "Cnc Budget Document Id Required" without it) and
    // its messages are read back the same way.
    val documentId = document?.id.orEmpty()
    val scope = remember(tool, departmentId, documentId) {
        ChatScope(
            tool = tool,
            departmentId = departmentId,
            budgetDocumentId = documentId,
            // Rooms belong to their own tool; messages always say
            // `main_budget_tool`, as all three of the web's send paths do.
            messageTool = BudgetViewer.MAIN_TOOL,
            // And every event of theirs is `budget:`-prefixed — the whole
            // conversation surface is a mirror of C&C's, not C&C itself.
            eventPrefix = "budget:",
        )
    }
    val model = remember(ready, scope) { ready.budgetChat(scope) }
    val state by model.state.collectAsState()
    val coroutines = rememberCoroutineScope()
    var starting by remember(scope) { mutableStateOf(false) }
    var complaint by remember(scope) { mutableStateOf<String?>(null) }

    // The rooms for this scope, and then the first of them — a budget's
    // discussion is its room, and making the reader pick from a list of one
    // would be furniture for its own sake.
    LaunchedEffect(model) { model.onEvent(ChatEvent.RefreshRecents) }
    LaunchedEffect(state.groups, state.peer) {
        if (state.peer == null) state.groups.firstOrNull()?.let { model.onEvent(ChatEvent.OpenGroup(it)) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ZillitSectionLabel(
            text = "Discussion",
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        )
        Box(Modifier.fillMaxSize()) {
            if (state.peer == null) {
                NoDiscussionYet(
                    canStart = canStart,
                    starting = starting,
                    complaint = complaint,
                    modifier = Modifier.align(Alignment.Center),
                    onStart = {
                        starting = true
                        complaint = null
                        coroutines.launch {
                            complaint = ready.startBudgetRoom(scope, document)
                            starting = false
                            model.onEvent(ChatEvent.RefreshRecents)
                        }
                    },
                )
            } else {
                ChatConversation(
                    state = state,
                    onEvent = model::onEvent,
                    seams = ChatSeams(canDownload = { true }),
                    resolveName = { id -> crewNameOf(ready, id) },
                    onOpenAttachment = { file -> openChatAttachment(ready, file) },
                )
            }
        }
    }
}

/**
 * A chat view model for one budget surface.
 *
 * Deliberately thinner than the C&C one: no presence, no favourites, no
 * picker seams that the budget pane offers no buttons for. What it does have
 * is the same repository, so messages, receipts and attachments behave
 * identically.
 */
/** Nothing to show, and — for someone with posting rights — a way to fix that. */
@Composable
private fun NoDiscussionYet(
    canStart: Boolean,
    starting: Boolean,
    complaint: String?,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ZillitEmptyState(
        title = "No discussion yet",
        message = complaint
            ?: if (canStart) {
                "Start one and everyone who can see this budget joins it."
            } else {
                "A budget room appears here once one is created for this budget."
            },
        icon = ZillitIcons.Chat,
        modifier = modifier,
        action = if (!canStart) {
            null
        } else {
            {
                ZillitButton(
                    text = "Start discussion",
                    onClick = onStart,
                    leadingIcon = ZillitIcons.Chat,
                    loading = starting,
                )
            }
        },
    )
}

/**
 * Opens a room for a budget, with everyone who can already see that budget in
 * it — the audience the service itself names (`/budget-users/{id}`), rather
 * than a picker asking the reader to reconstruct it by hand.
 *
 * Returns null on success, or the sentence to show when it fails.
 */
private suspend fun AppGraph.Ready.startBudgetRoom(scope: ChatScope, document: BudgetDocument?): String? {
    val budgets = BudgetRepositoryImpl(apiClient, config)
    val audience = (budgets.members(scope.departmentId) as? ZillitResult.Success)?.data
    // The creator is always in the room. Without this a budget whose audience
    // list is empty — which a live department budget's was — sends no members
    // at all, and the server refuses with "Cnc Atleast One Member": a
    // discussion nobody could ever start.
    val audienceIds = when (document?.type) {
        BudgetType.Department -> audience?.department
        else -> audience?.main
    }.orEmpty().map { it.userId }
    val me = projectContext?.context?.value?.profile?.userId.orEmpty()
    val people = (audienceIds + me).filter { it.isNotBlank() }.distinct()
    val name = document?.let { it.departmentName.ifBlank { "Budget" } } ?: "Main budget"
    return when (val made = chatRepositoryFor(scope).createRoom(name, people)) {
        is ZillitResult.Success -> null
        is ZillitResult.Failure -> made.error.localised()
    }
}

internal fun AppGraph.Ready.budgetChat(scope: ChatScope) = ChatViewModel(
    repository = chatRepositoryFor(scope),
    nowMillis = System::currentTimeMillis,
    newUniqueId = { java.util.UUID.randomUUID().toString() },
    offline = offlineSupport,
    uploadMedia = { name, type, bytes, onProgress -> uploadChatMedia(this, name, type, bytes, onProgress) },
    staticMap = { lat, lng -> fetchStaticMapBytes(this, lat, lng) },
)
