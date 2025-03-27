package com.auri.core.common

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.Raise
import arrow.core.raise.catch
import co.touchlab.kermit.Logger
import com.auri.core.common.util.chainIfNotNull


/*
interface Failure {
    val message: String
}


data class SimpleFailure(
    override val message: String
) : Failure

data class ExceptionFailure(
    override val message: String,
    val cause: Throwable
) : Failure

*/

/*inline fun <A> Raise<Nel<Failure>>.catch(message: String, block: () -> A): A =
    catch(
        block = block,
        catch = {
            raise(ExceptionFailure(message = message, cause = it).nel())
        }
    )*/

fun Throwable.ctx(context: String) = Throwable(
    message = context,
    cause = this
)

inline fun <B> Raise<Throwable>.catching(context: String? = null, block: () -> B): B =
    catch(
        block = block,
        catch = { raise(it.chainIfNotNull(context) { ctx(it) }) }
    )

fun <B> Either<Throwable, B>.ctx(context: String) = mapLeft { it.ctx(context) }


fun <B> Either<Throwable, B>.unwrap() = getOrElse { throw it }

fun <B> Either<Throwable, B>.ignore() = mapLeft { Logger.e(it) { "Exception ignored" } }