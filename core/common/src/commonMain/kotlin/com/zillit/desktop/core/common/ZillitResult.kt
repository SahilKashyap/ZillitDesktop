package com.zillit.desktop.core.common

/**
 * The single result type used across every layer.
 *
 * The Android app returns `Flow<ApiStatus<Any>>` and casts at the call site,
 * which is why a null in one response silently empties a whole list. This is
 * typed end to end, and errors are values rather than exceptions.
 */
sealed interface ZillitResult<out T> {

    data class Success<T>(val data: T) : ZillitResult<T>

    data class Failure(val error: ZillitError) : ZillitResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.data

    fun errorOrNull(): ZillitError? = (this as? Failure)?.error
}

inline fun <T, R> ZillitResult<T>.map(transform: (T) -> R): ZillitResult<R> = when (this) {
    is ZillitResult.Success -> ZillitResult.Success(transform(data))
    is ZillitResult.Failure -> this
}

inline fun <T, R> ZillitResult<T>.flatMap(transform: (T) -> ZillitResult<R>): ZillitResult<R> =
    when (this) {
        is ZillitResult.Success -> transform(data)
        is ZillitResult.Failure -> this
    }

inline fun <T> ZillitResult<T>.onSuccess(action: (T) -> Unit): ZillitResult<T> = apply {
    if (this is ZillitResult.Success) action(data)
}

inline fun <T> ZillitResult<T>.onFailure(action: (ZillitError) -> Unit): ZillitResult<T> = apply {
    if (this is ZillitResult.Failure) action(error)
}

fun <T> ZillitResult<T>.getOrElse(fallback: T): T = getOrNull() ?: fallback

fun <T> T.asSuccess(): ZillitResult<T> = ZillitResult.Success(this)

fun ZillitError.asFailure(): ZillitResult<Nothing> = ZillitResult.Failure(this)
