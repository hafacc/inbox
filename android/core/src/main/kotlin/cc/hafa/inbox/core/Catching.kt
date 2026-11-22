package cc.hafa.inbox.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * Run [block] as [runCatching] does, but let coroutine cancellation through: a
 * [CancellationException] is thrown on rather than caught, so a canceled coroutine stops instead
 * of carrying on as if [block] had merely failed. Use it, not [runCatching], around suspend calls.
 */
@Suppress("TooGenericExceptionCaught")
inline fun <R> runSuspendCatching(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
