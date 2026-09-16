package com.zillit.desktop.feature.chat.domain

/**
 * Who has and has not read one group message — the web's `ReadByUsers`
 * modal (`cnc_latest/components/ReadByUsers.jsx`), fed by
 * `GET group-chat/readby/{messageId}`.
 *
 * The rows carry user ids only; names and designations come from the crew
 * list at draw time, as the web's `findUser` does, and a row for someone no
 * longer on the crew is dropped there rather than shown as an id.
 */
data class ReadByReport(
    val read: List<ReadByRow> = emptyList(),
    val unread: List<ReadByRow> = emptyList(),
)

/**
 * One reader. [readAtMillis] is the wire's `read_time`, present on read rows
 * alone. [deliveredAtMillis] is `delivered` when it is a stamp — the web
 * prints it beside the read time, and prints "Today" for a literal `0`
 * (`ReadByUsers.jsx:137`); [isDelivered] is the unread list's question,
 * true for any truthy `delivered` (`ReadByUsers.jsx:169`).
 */
data class ReadByRow(
    val userId: String,
    val readAtMillis: Long? = null,
    val deliveredAtMillis: Long? = null,
    val isDelivered: Boolean = deliveredAtMillis != null && deliveredAtMillis != 0L,
)
