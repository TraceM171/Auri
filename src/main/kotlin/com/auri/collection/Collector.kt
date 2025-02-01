package com.auri.collection

import kotlinx.coroutines.flow.Flow

/**
 * Interface for a collector.
 *
 * A collector is a component that collects samples from a source and emits them as [RawCollectedSample]s.
 */
interface Collector {
    /**
     * The name of the collector. Must be unique for each collector.
     */
    val name: String

    /**
     * A description of the collector.
     */
    val description: String

    /**
     * The version of the collector.
     */
    val version: String

    /**
     * The source of the samples.
     */
    fun samples(): Flow<RawCollectedSample>
}