package com.zillit.desktop.feature.costumesetsync.data

import com.zillit.desktop.core.socket.SocketEventName

/**
 * Every `costume_set_sync:<entity>:<verb>` event the service emits (the web's
 * `socketEvents.js`, copied from the backend's own list).
 *
 * The frame is `{ project_id, entity, action, entity_id, data, user_id,
 * device_id }` with the full record in `data`. Screens just refetch on one.
 */
object SyncEvents {
    private fun group(entity: String, vararg verbs: String): Set<String> =
        verbs.map { "costume_set_sync:$entity:$it" }.toSet()

    val Actor = group("actor", "created", "updated", "deleted", "bulk_updated")
    val Character = group("character", "created", "updated", "deleted", "bulk_updated")
    val Scene = group("scene", "created", "updated", "deleted", "imported", "bulk_updated")
    val Change = group("change", "created", "updated", "deleted", "bulk_updated")
    val Costume = group("costume", "created", "updated", "deleted", "imported", "bulk_updated")
    val Fitting = group("fitting", "created", "updated", "deleted", "bulk_updated")

    /** No `deleted` — a ticket is cancelled through the pipeline, never removed. */
    val Cleaning = group("cleaning", "created", "updated", "bulk_updated")
    val Alteration = group("alteration", "created", "updated", "bulk_updated")

    /** No `created` — records are upserted by scene/character/take, so a first save arrives as `updated`. */
    val Continuity = group("continuity", "updated", "deleted", "bulk_updated")
    val Photo = group("photo", "created", "deleted")
    val Damage = group("damage", "created", "updated", "bulk_updated")
    val Missing = group("missing", "created", "updated", "bulk_updated")
    val Vendor = group("vendor", "created", "updated", "deleted")
    val Rental = group("rental", "created", "updated", "bulk_updated")
    val Contact = group("contact", "created", "updated", "deleted")
    val Expense = group("expense", "created", "updated", "deleted", "imported", "bulk_updated")
    val Comment = group("comment", "created", "deleted")
    val Cue = group("cue", "updated", "imported", "bulk_updated")
    val Schedule = group("schedule", "updated")
    val Member = group("member", "updated", "deleted")
    val Settings = group("settings", "updated")
    val Document = group("document", "created")

    val All: Set<String> = listOf(
        Actor, Character, Scene, Change, Costume, Fitting, Cleaning, Alteration, Continuity, Photo, Damage,
        Missing, Vendor, Rental, Contact, Expense, Comment, Cue, Schedule, Member, Settings, Document,
    ).flatten().toSet()

    val allNames: List<SocketEventName> = All.map(::SocketEventName)
}
