package com.auri.collection

import arrow.core.toNonEmptyListOrNull
import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.parMapNotNull
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
    private val cacheDir: File,
    private val samplesDir: File,
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
                val collectorWorkingDirectory = File(cacheDir, collector.name)
                collectorWorkingDirectory.mkdirs()
                collector.start(
                    Collector.CollectionParameters(
                        workingDirectory = collectorWorkingDirectory
                    )
                ).map { collector to it }
            }
            .merge()
            .parMap { (collector, status) ->
                updateStatusForCollector(collector, status)
                val newSampleStatus = (status as? CollectorStatus.NewSample) ?: return@parMap null
                if (!newSampleStatus.sample.hasValidFile()) {
                    Logger.w { "Invalid file for sample ${newSampleStatus.sample.name}, emitted by ${collector.name}: ${newSampleStatus.sample.executable}" }
                    return@parMap null
                }
                val hashes = newSampleStatus.sample.executable.hashes(
                    HashAlgorithms.MD5, HashAlgorithms.SHA1, HashAlgorithms.SHA256
                )
                SampleWithSourceAndHashes(newSampleStatus.sample, collector, hashes)
            }.filterNotNull()
            .onCompletion {
                _collectionStatus.update {
                    CollectionProcessStatus.Finished(
                        collectorsStatus = (it as? CollectionProcessStatus.Collecting)?.collectorsStatus ?: emptyMap(),
                        samplesCollectedByCollector = (it as? CollectionProcessStatus.Collecting)?.samplesCollectedByCollector
                            ?: emptyMap(),
                        totalSamplesCollected = (it as? CollectionProcessStatus.Collecting)?.totalSamplesCollected ?: 0
                    )
                }
            }.collect { (sample, source, hashes) ->
                val sampleFile = File(samplesDir, hashes[HashAlgorithms.SHA1]!!)
                val addedToDB = transaction(auriDB) {
                    RawSampleEntity.find {
                        RawSampleTable.sha256 eq hashes[HashAlgorithms.SHA256]!!
                    }.firstOrNull()?.let {
                        Logger.i { "Sample ${sample.name} already exists in the database, skipping" }
                        return@transaction false
                    }
                    val savedEntity = RawSampleEntity.new {
                        this.md5 = hashes[HashAlgorithms.MD5]!!
                        this.sha1 = hashes[HashAlgorithms.SHA1]!!
                        this.sha256 = hashes[HashAlgorithms.SHA256]!!
                        this.name = sample.name
                        this.sourceName = source.name
                        this.sourceVersion = source.version
                        this.path = sampleFile.relativeTo(samplesDir).path
                        this.collectionDate = java.time.LocalDate.now().toKotlinLocalDate()
                        this.submissionDate = sample.submissionDate
                    }
                    Logger.i { "Sample ${sample.name} saved to the database with ID ${savedEntity.id}" }
                    true
                }
                if (!addedToDB) return@collect
                sample.executable.copyTo(sampleFile)
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