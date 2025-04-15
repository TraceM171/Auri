package com.auri.core.common.util

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.right
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity


private class ContextualThrowable(
    message: String,
    override val cause: Throwable
) : Throwable(message, cause)

val Throwable.deCtx: Throwable
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

inline fun <B> catching(block: () -> B): Either<Throwable, B> = catch(
    block = { block().right() },
    catch = { it.left() }
)

fun <B> Either<Throwable, B>.ctx(context: String) = mapLeft { it.ctx(context) }

fun <B> Either<Throwable, B>.unwrap() = getOrElse { throw it }

fun <B> Either<Throwable, B>.ignore() = mapLeft { }

fun <B> Either<Throwable, B>.onLeftLog(
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