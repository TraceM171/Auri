package com.auri.collection.definition

import kotlinx.coroutines.flow.Flow
import java.io.File

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
     *
     * @param collectionParameters The parameters for the collection.
     */
    fun samples(
        collectionParameters: CollectionParameters
    ): Flow<RawCollectedSample>

    /**
     * Parameters for the collection.
     */
    data class CollectionParameters(
        /**
         * The working directory for the collection.
         *
         * Any files that are created during the collection will be created in this directory.
         */
        val workingDirectory: File,
        /**
         * If true, the cache will be invalidated before collecting samples.
         */
        val invalidateCache: Boolean,
    )
}