package com.zillit.desktop.feature.maps.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.maps.domain.TypeDraft

/**
 * Location types: the LOC Types panel (`headers/HeaderManagerPanel.jsx`), its
 * form (`HeaderForm.jsx`), and the New Type dialog the location form opens.
 *
 * One form, two homes. Which one is being edited is decided by where the form
 * currently lives: the dialog when it is open, the panel's inline card
 * otherwise.
 */
internal class TypeController(private val store: MapStore) {

    /** A type just created from the location form, to select there once the list has it. */
    var createdForForm: ((String) -> Unit)? = null

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per act.
    fun onEvent(event: MapEvent.Types) {
        when (event) {
            is MapEvent.Types.Filter -> store.update { copy(typesPanel = typesPanel.copy(filter = event.text)) }
            MapEvent.Types.New -> store.withPost {
                store.update { copy(typesPanel = typesPanel.copy(form = TypeFormState())) }
            }
            is MapEvent.Types.Edit -> store.withPost { edit(event.typeId) }
            is MapEvent.Types.Delete -> store.withPost { askDelete(event.typeId) }
            is MapEvent.Types.Name -> updateForm { copy(name = event.value) }
            is MapEvent.Types.Icon -> updateForm { copy(icon = event.icon, iconPickerOpen = false) }
            is MapEvent.Types.IconPicker -> updateForm { copy(iconPickerOpen = event.open) }
            is MapEvent.Types.NewSubType -> updateForm { copy(newSubType = event.value) }
            MapEvent.Types.AddSubType -> updateForm {
                val trimmed = newSubType.trim()
                if (trimmed.isEmpty() || trimmed in subTypes) this else copy(subTypes = subTypes + trimmed, newSubType = "")
            }
            is MapEvent.Types.RemoveSubType -> updateForm {
                copy(subTypes = subTypes.filterIndexed { index, _ -> index != event.index })
            }
            MapEvent.Types.Save -> save()
            MapEvent.Types.Cancel -> cancel()
        }
    }

    /** Opens New Type over the location form, seeded with what was typed in its search. */
    fun openDialog(seedName: String) = store.update {
        copy(dialog = MapDialog.NewType(TypeFormState(name = seedName)))
    }

    private fun edit(typeId: String) {
        val type = store.state.types.firstOrNull { it.id == typeId } ?: return
        store.update {
            copy(
                typesPanel = typesPanel.copy(
                    form = TypeFormState(editId = type.id, name = type.name, icon = type.icon, subTypes = type.subTypes),
                ),
            )
        }
    }

    private fun currentForm(): TypeFormState? =
        (store.state.dialog as? MapDialog.NewType)?.form ?: store.state.typesPanel.form

    private fun updateForm(reducer: TypeFormState.() -> TypeFormState) = store.update {
        when (val open = dialog) {
            is MapDialog.NewType -> copy(dialog = MapDialog.NewType(open.form.reducer()))
            else -> copy(typesPanel = typesPanel.copy(form = typesPanel.form?.reducer()))
        }
    }

    private fun cancel() = store.update {
        if (dialog is MapDialog.NewType) copy(dialog = null) else copy(typesPanel = typesPanel.copy(form = null))
    }

    /**
     * Save. Editing asks first — renaming a type relabels every location that
     * uses it (`handleSave` → the update confirmation).
     */
    private fun save() {
        val form = currentForm() ?: return
        if (form.name.isBlank()) return
        val editId = form.editId
        if (editId != null) {
            store.update {
                copy(
                    dialog = MapDialog.Confirm(
                        title = "Update Location Type",
                        message = "Updating ${store.state.types.firstOrNull { it.id == editId }?.name ?: form.name} " +
                            "will change the type classification for associated locations. These locations will " +
                            "display the updated type label. Do you want to proceed?",
                        confirmLabel = "Update",
                        danger = false,
                        action = ConfirmAction.UpdateType(editId, form),
                    ),
                )
            }
            return
        }
        val fromDialog = store.state.dialog is MapDialog.NewType
        updateForm { copy(saving = true) }
        store.spawn {
            when (val result = store.repository.createType(form.draft())) {
                is ZillitResult.Failure -> {
                    updateForm { copy(saving = false) }
                    store.failed(result.error, "Failed to create location type")
                }
                is ZillitResult.Success -> {
                    store.notice("Location type created successfully", NoticeTone.Success)
                    cancel()
                    reload()
                    if (fromDialog) createdForForm?.invoke(form.name.trim())
                }
            }
        }
    }

    fun update(typeId: String, form: TypeFormState, done: () -> Unit) {
        store.spawn {
            when (val result = store.repository.updateType(typeId, form.draft())) {
                is ZillitResult.Failure -> store.failed(result.error, "Failed to update location type")
                is ZillitResult.Success -> {
                    store.notice("Location type updated successfully", NoticeTone.Success)
                    store.update { copy(typesPanel = typesPanel.copy(form = null)) }
                    reload()
                    // A renamed type relabels its locations on the server.
                    store.hooks.reloadLocations()
                }
            }
            done()
        }
    }

    private fun askDelete(typeId: String) {
        val type = store.state.types.firstOrNull { it.id == typeId } ?: return
        store.update {
            copy(
                dialog = MapDialog.Confirm(
                    title = "Delete Location Type",
                    message = "Deleting ${type.name} will remove its type classification from associated " +
                        "locations. Those locations will appear without a type label. Do you want to continue?",
                    confirmLabel = "Delete",
                    danger = true,
                    action = ConfirmAction.DeleteType(typeId),
                ),
            )
        }
    }

    fun delete(typeId: String, done: () -> Unit) {
        store.spawn {
            when (val result = store.repository.deleteType(typeId)) {
                is ZillitResult.Failure -> store.failed(result.error, "Failed to delete location type")
                is ZillitResult.Success -> {
                    store.notice("Location type deleted successfully", NoticeTone.Success)
                    reload()
                    store.hooks.reloadLocations()
                }
            }
            done()
        }
    }

    fun reload() {
        store.spawn {
            when (val result = store.repository.types()) {
                is ZillitResult.Success -> store.update { copy(types = result.data) }
                is ZillitResult.Failure -> store.failed(result.error, "Failed to fetch location types")
            }
        }
    }

    private fun TypeFormState.draft() = TypeDraft(name = name.trim(), icon = icon, subTypes = subTypes)
}
