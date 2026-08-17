package com.zillit.desktop.core.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

/**
 * The production engine.
 *
 * Note what is *not* here: no `preconfigured` OkHttpClient, no
 * `sslSocketFactory`, no `hostnameVerifier`. OkHttp's defaults use the platform
 * trust store, which is the correct behaviour and the whole point.
 *
 * Certificate pinning (plan §8.2) attaches here once the pin set is available
 * from the backend — as a `CertificatePinner`, which *tightens* validation.
 * Nothing in this file may ever loosen it.
 */
class OkHttpEngineProvider : HttpClientEngineProvider {
    override fun engine(): HttpClientEngineFactory<*> = OkHttp
}
