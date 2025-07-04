package com.auri.core.common.util

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.right
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

typealias Fallible<T> = Either<Throwable, T>

private class ContextualThrowable(
    message: String,
    override val cause: Throwable
) : Throwable(message, cause)


fun failure(message: String) = Throwable(message)


val Throwable.decontextualized: Throwable
    get() = when (this) {
        is ContextualThrowable -> cause
        else -> this
    }

val Throwable.messageWithCtx: String?
    get() = when (this) {
        is ContextualThrowable -> "$message -> ${cause.messageWithCtx}"
        else -> message
    }

fun Throwable.ctx(context: String): Throwable = ContextualThrowable(
    message = context,
    cause = this
)

fun Throwable.suppresses(suppressed: Throwable): Throwable = apply { addSuppressed(suppressed) }


fun <B> Fallible<B>.ctx(context: String) = mapLeft { it.ctx(context) }

fun <B> Fallible<B>.suppresses(suppressed: Throwable) = onLeft { it.suppresses(suppressed) }

fun <B> Fallible<B>.unwrap() = getOrElse { throw it }

fun <B> Fallible<B>.ignore() = mapLeft { }

fun <B> Fallible<B>.onLeftLog(
    severity: Severity = Severity.Error,
    onlyMessage: Boolean = false
) = onLeft {
    if (Logger.config.minSeverity > severity) return@onLeft
    Logger.log(
        tag = "",
        severity = severity,
        throwable = it.takeUnless { onlyMessage },
        message = "${it.messageWithCtx}"
    )
}


inline fun <B> catching(block: () -> B): Fallible<B> = catch(
    block = { block().right() },
    catch = { it.left() }
)