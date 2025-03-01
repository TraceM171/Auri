package com.auri.extensions.collection.common

import com.auri.core.collection.Collector
import com.auri.core.collection.CollectorStatus
import com.auri.core.collection.CollectorStatus.*
import com.auri.core.common.util.PeriodicActionConfig
import com.auri.core.common.util.perform
import kotlinx.coroutines.flow.*

fun periodicCollection(
    periodicity: PeriodicActionConfig?,
    collectionParameters: Collector.CollectionParameters,
    singleCollection: (collectionParameters: Collector.CollectionParameters) -> Flow<CollectorStatus>
): Flow<CollectorStatus> = flow {
    val safeCollectorFlow = singleCollection(collectionParameters)
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

    periodicity.perform<Unit, Unit> {
        val lastStatus = safeCollectorFlow
            .map {
                when (it) {
                    is Failed -> Retrying(what = it.what, why = it.why)
                    is Done -> DoneUntilNextPeriod
                    else -> it
                }
            }.transformWhile {
                emit(it)
                when (it) {
                    Done,
                    DoneUntilNextPeriod,
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
            DoneUntilNextPeriod,
            is Downloading,
            is NewSample,
            is Processing,
            null -> Unit

            is Failed,
            is Retrying -> raise(Unit)
        }
    }.collect()
}