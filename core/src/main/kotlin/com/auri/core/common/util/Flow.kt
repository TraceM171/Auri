package com.auri.core.common.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlin.time.Duration

/**
 * Make this flow re-emit last value if a max [interval] has passed since last emission.
 */
fun <T> Flow<T>.atLeastEvery(interval: Duration): Flow<T> {
    val minUpdateRateFlow = flow {
        while (true) {
            delay(interval)
            emit(Unit)
        }
    }
    var finished = false
    return onCompletion {
        finished = true
    }.combine(minUpdateRateFlow) { it, _ -> it }
        .transformWhile {
            emit(it)
            !finished
        }
}