package com.auri.core.common.util

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.resilience.Schedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration

data class PeriodicActionConfig(
    val performEvery: Duration,
    val maxRetriesPerPerform: Int,
    val skipPerformIfFailed: Boolean,
    val retryEvery: Duration
)

fun <Error, A> PeriodicActionConfig.perform(
    action: suspend Raise<Error>.() -> A
): Flow<Either<Error, A>> = flow {
    Schedule
        .spaced<Either<Error, A>>(performEvery)
        .doWhile { input, _ -> input.isRight() || skipPerformIfFailed }
        .repeat {
            (Schedule.identity<Either<Error, A>>()
                    zipLeft Schedule.spaced(retryEvery)
                    zipLeft Schedule.recurs(maxRetriesPerPerform.toLong()))
                .doUntil { input, _ -> input.isRight() }
                .repeat {
                    either { action() }.also { emit(it) }
                }
        }
}
