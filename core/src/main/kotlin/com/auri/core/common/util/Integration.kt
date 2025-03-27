package com.auri.core.common.util

import arrow.core.raise.Raise
import arrow.core.raise.catch
import arrow.fx.coroutines.AcquireStep
import arrow.fx.coroutines.ResourceScope
import co.touchlab.kermit.Logger

inline fun <T> Raise<Unit>.catchLog(
    errorToLog: String,
    block: () -> T
) = catch(
    block = block,
    catch = {
        Logger.e(throwable = it, messageString = errorToLog)
        raise(Unit)
    }
)

suspend inline fun <A> ResourceScope.installF(
    noinline acquire: suspend AcquireStep.() -> A,
    crossinline release: suspend (A) -> Unit,
): A = install(
    acquire = acquire,
    release = { it, _ -> release(it) }
)