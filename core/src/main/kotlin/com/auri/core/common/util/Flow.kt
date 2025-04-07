package com.auri.core.common.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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

fun BufferedReader.linesFlow(): Flow<String> = flow {
    val stringBuilder = StringBuilder()
    while (true) {
        val ready = runCatching(BufferedReader::ready).getOrNull() ?: break
        if (!ready) {
            delay(100.milliseconds)
            continue
        }
        val byte = read()
        if (byte == -1) break
        val char = byte.toChar()
        if (char == '\n') {
            emit(stringBuilder.toString())
            stringBuilder.clear()
            continue
        }
        stringBuilder.append(char)
    }
}
