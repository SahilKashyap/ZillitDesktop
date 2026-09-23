package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.FolderNameError
import com.zillit.desktop.feature.email.domain.FolderRepository
import com.zillit.desktop.feature.email.domain.isDeletable
import com.zillit.desktop.feature.email.domain.validateFolderName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The folder dialog.
 *
 * One state for creating and renaming: they differ only in whether there is a
 * folder being renamed, and splitting them would duplicate the name, the
 * validation and the error.
 */
data class FolderEdit(
    /** Null when creating. */
    val renaming: EmailFolder? = null,
    val name: String = "",
    val error: FolderNameError? = null,
    val isSaving: Boolean = false,
) {
    val isRename: Boolean get() = renaming != null

    val title: String get() = str(if (isRename) S.rename_folder else S.drive_pick_new_folder)

    val action: String get() = str(if (isRename) S.rename else S.create)
}

/**
 * Creating, renaming and deleting folders.
 *
 * Its own class rather than more of the mailbox's, on the third occasion that
 * class hit its size limit — and this is the part with the least to do with the
 * rest of it: it owns one dialog, three calls and a set of naming rules, and
 * touches no mail.
 *
 * Deleting is *asked for* here but carried out by the mailbox, because it has
 * consequences this class has no business knowing about — the open folder may
 * have to move, and the local cache has to lose the mail.
 */
class FolderEditor(private val repository: FolderRepository) {

    private val _state = MutableStateFlow<FolderEdit?>(null)
    val state: StateFlow<FolderEdit?> = _state

    /** [folder] is null to create a new one. */
    fun open(folder: EmailFolder?) {
        _state.value = FolderEdit(renaming = folder, name = folder?.name.orEmpty())
    }

    fun dismiss() {
        _state.value = null
    }

    fun nameChanged(value: String) {
        // The error clears as they type rather than persisting until the next
        // save, which would leave a red field they have just fixed.
        _state.update { it?.copy(name = value, error = null) }
    }

    /**
     * Creates or renames, whichever the dialog was opened for.
     *
     * Returns the resulting folder name on success, so the caller can follow a
     * rename with the selection. Null means nothing happened — either the name
     * was refused here, or the server refused it.
     */
    suspend fun save(existing: List<EmailFolder>): ZillitResult<String>? {
        val edit = _state.value ?: return null
        val name = edit.name.trim()

        // Validated before the request: two of the rules — the duplicate and
        // the reserved name — the client already knows, and a round trip to be
        // told so is a round trip wasted.
        val error = validateFolderName(name, existing, edit.renaming?.name)
        if (error != null) {
            _state.value = edit.copy(error = error)
            return null
        }

        _state.value = edit.copy(isSaving = true)

        val result = edit.renaming
            ?.let { repository.renameFolder(it.name, name) }
            ?: repository.createFolder(name)

        return when (result) {
            is ZillitResult.Success -> {
                _state.value = null
                ZillitResult.Success(name)
            }
            is ZillitResult.Failure -> {
                // The dialog stays open with the name still in it. Losing a
                // typed name to a network blip is a small thing done twice.
                _state.value = edit.copy(isSaving = false)
                result
            }
        }
    }

    /** The folder the dialog is editing, if it may be deleted. */
    fun folderToDelete(): EmailFolder? =
        _state.value?.renaming?.takeIf { it.isDeletable }?.also { dismiss() }

    suspend fun delete(folder: EmailFolder): ZillitResult<Unit> = repository.deleteFolder(folder.name)
}
