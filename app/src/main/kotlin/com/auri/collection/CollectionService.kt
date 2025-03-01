package com.auri.collection

import arrow.core.toNonEmptyListOrNull
import arrow.fx.coroutines.parMapNotNull
import arrow.fx.coroutines.parMapNotNullUnordered
import co.touchlab.kermit.Logger
import com.auri.common.data.entity.RawSampleEntity
import com.auri.common.data.entity.RawSampleTable
import com.auri.core.collection.Collector
import com.auri.core.collection.CollectorStatus
import com.auri.core.collection.RawCollectedSample
import com.auri.core.common.util.HashAlgorithms
import com.auri.core.common.util.hashes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

class CollectionService(
    private val workingDirectory: File,
    private val invalidateCache: Boolean,
    private val auriDB: Database,
    private val collectors: List<Collector>
) {
    private val _collectionStatus: MutableStateFlow<CollectionProcessStatus> =
        MutableStateFlow(CollectionProcessStatus.NotStarted)
    val collectionStatus = _collectionStatus.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    suspend fun run() {
        _collectionStatus.update { CollectionProcessStatus.Initializing }
        val missingDependencies = collectors.parMapNotNull { collector ->
            collector.checkDependencies().toNonEmptyListOrNull()?.let { collector to it }
        }.associate { it }
        if (missingDependencies.isNotEmpty()) {
            _collectionStatus.update { CollectionProcessStatus.MissingDependencies(missingDependencies) }
            return
        }
        _collectionStatus.update {
            CollectionProcessStatus.Collecting(
                collectorsStatus = collectors.associateWith { null },
                samplesCollectedByCollector = collectors.associateWith { 0 },
                totalSamplesCollected = 0
            )
        }

        collectors
            .map { collector ->
                val collectorWorkingDirectory = File(workingDirectory, collector.name)
                if (invalidateCache) {
                    Logger.d { "Invalidating cache for collector ${collector.name}" }
                    collectorWorkingDirectory.deleteRecursively()
                }
                collectorWorkingDirectory.mkdirs()
                collector.start(
                    Collector.CollectionParameters(
                        workingDirectory = collectorWorkingDirectory
                    )
                ).map { collector to it }
            }
            .merge()
            .parMapNotNullUnordered { (collector, status) ->
                updateStatusForCollector(collector, status)
                val newSampleStatus = (status as? CollectorStatus.NewSample) ?: return@parMapNotNullUnordered null
                if (!newSampleStatus.sample.hasValidFile()) {
                    Logger.w { "Invalid file for sample ${newSampleStatus.sample.name}, emitted by ${collector.name}: ${newSampleStatus.sample.executable}" }
                    return@parMapNotNullUnordered null
                }
                val hashes = newSampleStatus.sample.executable.hashes(
                    HashAlgorithms.MD5, HashAlgorithms.SHA1, HashAlgorithms.SHA256
                )
                SampleWithSourceAndHashes(newSampleStatus.sample, collector, hashes)
            }.onCompletion {
                _collectionStatus.update {
                    CollectionProcessStatus.Finished(
                        collectorsStatus = (it as? CollectionProcessStatus.Collecting)?.collectorsStatus ?: emptyMap(),
                        samplesCollectedByCollector = (it as? CollectionProcessStatus.Collecting)?.samplesCollectedByCollector
                            ?: emptyMap(),
                        totalSamplesCollected = (it as? CollectionProcessStatus.Collecting)?.totalSamplesCollected ?: 0
                    )
                }
            }.collect { (sample, source, hashes) ->
                transaction(auriDB) {
                    RawSampleEntity.find {
                        RawSampleTable.sha256 eq hashes[HashAlgorithms.SHA256]!!
                    }.firstOrNull()?.let {
                        Logger.i { "Sample ${sample.name} already exists in the database, skipping" }
                        return@transaction
                    }
                    val relativePath = sample.executable.relativeToOrNull(workingDirectory)?.path ?: run {
                        Logger.w { "Failed to get relative path for sample ${sample.name}, it will not be saved to the database" }
                        return@transaction
                    }
                    val savedEntity = RawSampleEntity.new {
                        this.md5 = hashes[HashAlgorithms.MD5]!!
                        this.sha1 = hashes[HashAlgorithms.SHA1]!!
                        this.sha256 = hashes[HashAlgorithms.SHA256]!!
                        this.name = sample.name
                        this.sourceName = source.name
                        this.sourceVersion = source.version
                        this.path = relativePath
                        this.collectionDate = java.time.LocalDate.now().toKotlinLocalDate()
                        this.submissionDate = sample.submissionDate
                    }
                    Logger.i { "Sample ${sample.name} saved to the database with ID ${savedEntity.id}" }
                }
                _collectionStatus.update { currentStatus ->
                    (currentStatus as? CollectionProcessStatus.Collecting)
                        ?.let {
                            it.copy(
                                samplesCollectedByCollector = it.samplesCollectedByCollector.toMutableMap().apply {
                                    this[source] = (this[source] ?: 0) + 1
                                },
                                totalSamplesCollected = it.totalSamplesCollected + 1
                            )
                        } ?: currentStatus
                }
            }
    }

    private fun updateStatusForCollector(
        collector: Collector,
        status: CollectorStatus
    ) = _collectionStatus.update { currentStatus ->
        (currentStatus as? CollectionProcessStatus.Collecting)
            ?.let {
                it.copy(
                    it.collectorsStatus.toMutableMap().apply {
                        this[collector] = status
                    }
                )
            }
            ?: currentStatus
    }

    private fun RawCollectedSample.hasValidFile(): Boolean {
        if (!executable.exists()) return false
        if (!executable.isFile) return false
        if (!executable.canRead()) return false
        return true
    }

    private data class SampleWithSourceAndHashes(
        val sample: RawCollectedSample,
        val source: Collector,
        val hashes: Map<HashAlgorithms, String>
    )
}