package com.zillit.desktop.feature.chat.domain

/**
 * Where a forwarded message goes: a person's DM thread or a room.
 *
 * The web's "Forward In App" picker (`ForwardMsgModal.jsx`) offers chat
 * users, chat groups and the film-tool boards; this client offers the two
 * chat halves, which are the destinations it can reach from here. Each
 * target gets its own copy — a fresh `unique_id`, the same words, file and
 * place (`cncUtil.js` `generateForwardSingleMessagePayload`).
 */
data class ForwardTarget(val id: String, val isGroup: Boolean)
