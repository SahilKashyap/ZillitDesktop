package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.feature.documentdistribution.data.DocDistRepositoryImpl
import com.zillit.desktop.feature.documentdistribution.domain.DocumentStorage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a document is reached.
 *
 * There was a `/documents/:id/download-url` call here until 2026-08-27. That
 * route does not exist — checked against the dev server, which answers 404 for
 * it and 406 (`libs_module_data_invalid`) for the `/raw` proxy beside it. So
 * every open and every download in this tool failed, and the fallback written
 * beneath the call was unreachable: a 404 is a failure, not a null body.
 *
 * The engine here refuses every request, which is the point — resolving a
 * document's URL must not touch the network at all.
 */
class DocumentUrlTest {

    private fun repository(presign: suspend (DocumentStorage) -> String?) = DocDistRepositoryImpl(
        apiClient = ApiClient(
            httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        ),
        config = AppConfig(
            environment = Environment.Develop,
            // Every service, because resolving one endpoint validates them all.
            services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.example.test" },
            realtime = emptyMap(),
        ),
        presign = presign,
    )

    private fun document(storage: DocumentStorage?) =
        LibraryDocument(id = "d1", name = "Call Sheet Day 11.pdf", storage = storage)

    private val s3 = DocumentStorage(key = "docs/day 11.pdf", bucket = "b", region = "eu-west-2")

    @Test
    fun `an s3 document is reached by its presigned url`() = runTest {
        val answer = repository { "https://signed" }.documentUrl(document(s3))

        assertEquals(ZillitResult.Success("https://signed"), answer)
    }

    /** The presigner is handed exactly what the listing named, unaltered. */
    @Test
    fun `the object presigned is the one the listing named`() = runTest {
        var seen: DocumentStorage? = null

        repository { storage -> seen = storage; "u" }.documentUrl(document(s3))

        assertEquals(s3, seen)
    }

    /**
     * A LOCAL production's bytes are proxied by a route the desktop cannot
     * open in a browser. Saying so is the point: the old code handed back a
     * URL that 404'd, which reads as a broken file rather than an unsupported
     * one.
     */
    @Test
    fun `a document with no attachment is refused with a reason`() = runTest {
        val answer = repository { "unused" }.documentUrl(document(storage = null))

        assertTrue(answer is ZillitResult.Failure, "a proxied document must not claim a URL")
    }

    /** No credentials is a workspace problem, and is reported as one. */
    @Test
    fun `no credentials is refused rather than guessed at`() = runTest {
        val answer = repository { null }.documentUrl(document(s3))

        assertTrue(answer is ZillitResult.Failure)
    }
}
