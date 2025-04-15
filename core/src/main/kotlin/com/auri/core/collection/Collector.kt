package com.auri.core.collection

import com.auri.core.common.ExtensionPoint
import com.auri.core.common.HasDependencies
import com.auri.core.common.MissingDependency
import com.auri.core.common.util.HashAlgorithms
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * Interface for a collector.
 *
 * A collector is a component that collects samples from a source and emits them as [RawCollectedSample]s.
 */
@ExtensionPoint
interface Collector : HasDependencies, AutoCloseable {
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
     * @param workingDirectory The directory where the collector may store its data.
     * @param checkSampleExistence A function that checks if a sample with the given hash already exists and can be skipped.
     * This can be used by the collector to avoid re-collecting samples that are already present, but it is not mandatory,
     * as duplicate samples will be filtered out externally.
     * @return A flow of [CollectorStatus] objects that represent changes in the collection status.
     */
    fun start(
        workingDirectory: Path,
        checkSampleExistence: suspend (hashType: HashAlgorithms, hash: String) -> Boolean,
    ): Flow<CollectorStatus>

    override suspend fun checkDependencies(): List<MissingDependency> = emptyList()

    override fun close() = Unit
}