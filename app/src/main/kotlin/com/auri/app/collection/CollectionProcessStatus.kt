package com.auri.app.collection

import arrow.core.Nel
import com.auri.core.collection.Collector
import com.auri.core.collection.CollectorStatus
import com.auri.core.collection.InfoProvider
import com.auri.core.common.MissingDependency

sealed interface CollectionProcessStatus {
    data object NotStarted : CollectionProcessStatus
    data object Initializing : CollectionProcessStatus
    data class Failed(val what: String, val why: String) : CollectionProcessStatus
    data class MissingDependencies(val missingDependencies: Map<Collector, Nel<MissingDependency>>) :
        CollectionProcessStatus

    data class Collecting(
        val collectionStats: CollectionStats
    ) : CollectionProcessStatus

    data class Finished(
        val collectionStats: CollectionStats
    ) : CollectionProcessStatus


    data class CollectionStats(
        val collectorsStatus: Map<Collector, CollectorStatus?>,
        val samplesCollectedByCollector: Map<Collector, Int>,
        val totalSamplesCollected: Int,
        val samplesWithInfoByProvider: Map<InfoProvider, Int>,
        val totalSamplesWithInfo: Int
    )
}