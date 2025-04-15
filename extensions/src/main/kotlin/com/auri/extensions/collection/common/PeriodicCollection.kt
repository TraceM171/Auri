package com.auri.extensions.collection.common

import com.auri.core.collection.CollectorStatus
import com.auri.core.collection.CollectorStatus.*
import com.auri.core.common.util.PeriodicActionConfig
import com.auri.core.common.util.perform
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock

fun periodicCollection(
    periodicity: PeriodicActionConfig?,
    singleCollection: () -> Flow<CollectorStatus>
): Flow<CollectorStatus> = flow {
    val safeCollectorFlow = singleCollection()
        .catch {
            emit(Failed(what = "Collecting samples", why = it.message ?: "Unknown error"))
        }.onCompletion {
            if (it != null) return@onCompletion
            emit(Done)
        }

    if (periodicity == null) {
        emitAll(safeCollectorFlow)
        emit(Done)
        return@flow
    }

    var retryCount = 0

    periodicity.perform<Unit, Unit> {
        val lastStatus = safeCollectorFlow
            .map {
                when (it) {
                    is Failed -> {
                        retryCount++
                        if (retryCount > periodicity.maxRetriesPerPerform) {
                            retryCount = 0
                            Retrying(
                                what = it.what,
                                why = it.why,
                                nextTryStart = Clock.System.now() + periodicity.performEvery
                            )
                        } else Retrying(
                            what = it.what,
                            why = it.why,
                            nextTryStart = Clock.System.now() + periodicity.retryEvery
                        )
                    }

                    is Done -> {
                        retryCount = 0
                        DoneUntilNextPeriod(
                            nextPeriodStart = Clock.System.now() + periodicity.performEvery
                        )
                    }

                    else -> it
                }
            }.transformWhile {
                emit(it)
                when (it) {
                    Done,
                    is DoneUntilNextPeriod,
                    is Retrying,
                    is Failed -> false

                    is Downloading,
                    is NewSample,
                    is Processing -> true
                }
            }.onEach {
                emit(it)
            }.lastOrNull()
        when (lastStatus) {
            Done,
            is DoneUntilNextPeriod,
            is Downloading,
            is NewSample,
            is Processing,
            null -> Unit

            is Failed,
            is Retrying -> raise(Unit)
        }
    }.collect()
}