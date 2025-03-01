package com.auri.core.collection

import com.auri.core.common.ExtensionPoint
import com.auri.core.common.HasDependencies
import com.auri.core.common.MissingDependency
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Interface for a collector.
 *
 * A collector is a component that collects samples from a source and emits them as [RawCollectedSample]s.
 */
@ExtensionPoint
interface Collector : HasDependencies {
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
     * Starts the collection process.
     * 
     * @param collectionParameters The parameters for the collection.
     * @return A flow of [CollectorStatus] objects that represent changes in the collection status.
     */
    fun start(
        collectionParameters: CollectionParameters
    ): Flow<CollectorStatus>

    override suspend fun checkDependencies(): List<MissingDependency> = emptyList()

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
    )
}