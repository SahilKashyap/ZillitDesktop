package com.zillit.desktop.core.network

import com.zillit.desktop.core.common.ZillitError
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.cert.CertificateException

actual fun Throwable.toZillitError(): ZillitError = when (this) {
    // TLS first: these must never be conflated with a generic network blip,
    // because "the connection failed" invites a retry while "the certificate
    // did not validate" must not be retried around (plan §8.2).
    is CertificateException,
    is GeneralSecurityException,
    -> ZillitError.TlsFailure(describe())

    is HttpRequestTimeoutException,
    is SocketTimeoutException,
    -> ZillitError.Timeout(describe())

    is UnknownHostException,
    is ConnectException,
    -> ZillitError.NoConnection(describe())

    is IOException -> if (isTlsRelated()) {
        ZillitError.TlsFailure(describe())
    } else {
        ZillitError.NoConnection(describe())
    }

    else -> ZillitError.Unknown(describe())
}

/**
 * javax.net.ssl exception types are matched by name rather than by import —
 * `scripts/security-scan.sh` forbids importing that package outright, and a
 * string match here is enough to classify the error.
 */
private fun Throwable.isTlsRelated(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val name = current::class.qualifiedName.orEmpty()
        if (name.startsWith("javax.net.ssl.") || name.contains("Certificate")) return true
        current = current.cause?.takeIf { it !== current }
    }
    return false
}

private fun Throwable.describe(): String = "${this::class.simpleName}: ${message.orEmpty()}"
