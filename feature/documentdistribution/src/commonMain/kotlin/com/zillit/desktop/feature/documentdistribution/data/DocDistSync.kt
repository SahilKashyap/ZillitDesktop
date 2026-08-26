package com.zillit.desktop.feature.documentdistribution.data

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRefresh
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * The realtime events Document Distribution refreshes on, by wire name —
 * the `document_distribution:*` family is bridged verbatim
 * (`listenerSocket.js:2671-2693`), and the email service's open pixel
 * arrives as `outbound:email:opened` (`listenerSocket.js:1731-1733`).
 *
 * Which destination an event refreshes follows the web page that listens:
 *
 *  - folder/document mutations → the library
 *    (`useDocumentDistribution.js:132-138`, reload page 0 silently);
 *  - `distribution:sent` / `distribution:opened` and the open pixel →
 *    history (`HistoryDrawer.jsx:726-741` and `:282-291`);
 *  - `preset:*` → the saved recipient lists (`PresetManagerModal.jsx:312-314`);
 *  - `template:*` → the email templates (`EmailTemplateManager.jsx:156-158`).
 *
 * `document_distribution:publication:added/deleted` are on the wire
 * (`listenerSocket.js:2684-2685`) but no web page listens to them, so they
 * are deliberately not here.
 */
internal val DOC_DIST_REFRESH_BY_EVENT: Map<SocketEventName, DocDistRefresh> = buildMap {
    listOf(
        "document_distribution:folder:added",
        "document_distribution:folder:updated",
        "document_distribution:folder:deleted",
        "document_distribution:folder:moved",
        "document_distribution:document:added",
        "document_distribution:document:deleted",
        "document_distribution:document:moved",
    ).forEach { put(SocketEventName(it), DocDistRefresh.Library) }
    put(SocketEventName("document_distribution:distribution:sent"), DocDistRefresh.History)
    put(SocketEventName("document_distribution:distribution:opened"), DocDistRefresh.History)
    put(SocketEventName("outbound:email:opened"), DocDistRefresh.History)
    put(SocketEventName("document_distribution:preset:added"), DocDistRefresh.Lists)
    put(SocketEventName("document_distribution:preset:updated"), DocDistRefresh.Lists)
    put(SocketEventName("document_distribution:preset:deleted"), DocDistRefresh.Lists)
    put(SocketEventName("document_distribution:template:added"), DocDistRefresh.Templates)
    put(SocketEventName("document_distribution:template:updated"), DocDistRefresh.Templates)
    put(SocketEventName("document_distribution:template:deleted"), DocDistRefresh.Templates)
}

/**
 * The refresh pulses for a bus, or empty without one. Conflated: a send
 * emits document and distribution events back to back and one refetch per
 * destination answers all.
 */
internal fun docDistRefreshes(bus: SocketEventBus?): Flow<DocDistRefresh> =
    bus?.onAny(DOC_DIST_REFRESH_BY_EVENT.keys)
        ?.mapNotNull { DOC_DIST_REFRESH_BY_EVENT[it.event] }
        ?.conflate()
        ?: emptyFlow()
