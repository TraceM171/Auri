package com.auri.app.collection

import arrow.core.toNonEmptyListOrNull
import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.parMapNotNull
import co.touchlab.kermit.Logger
import com.auri.app.common.data.entity.RawSampleEntity
import com.auri.app.common.data.entity.RawSampleTable
import com.auri.app.common.data.entity.SampleInfoEntity
import com.auri.core.collection.Collector
import com.auri.core.collection.CollectorStatus
import com.auri.core.collection.InfoProvider
import com.auri.core.collection.RawCollectedSample
import com.auri.core.common.util.HashAlgorithms
import com.auri.core.common.util.hashes
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.*
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.*

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
internal class CollectionService(
    private val cacheDir: Path,
    private val samplesDir: Path,
    private val auriDB: Database,
    private val collectors: List<Collector>,
    private val infoProviders: List<InfoProvider>
) {
    private val _collectionStatus: MutableStateFlow<CollectionProcessStatus> =
        MutableStateFlow(CollectionProcessStatus.NotStarted)
    val collectionStatus = _collectionStatus.asStateFlow()

    suspend fun run() {
        coroutineScope {
            val collectionFlow = launchCollectionStep(capacity = Channel.UNLIMITED).consumeAsFlow()
            launchInfoProviderStep(collectionFlow)
        }
        _collectionStatus.update {
            val collectingOrNull = (it as? CollectionProcessStatus.Collecting ?: return@update it).collectionStats
            CollectionProcessStatus.Finished(
                CollectionProcessStatus.CollectionStats(
                    collectorsStatus = collectingOrNull.collectorsStatus,
                    samplesCollectedByCollector = collectingOrNull.samplesCollectedByCollector,
                    totalSamplesCollected = collectingOrNull.totalSamplesCollected,
                    samplesWithInfoByProvider = collectingOrNull.samplesWithInfoByProvider,
                    totalSamplesWithInfo = collectingOrNull.totalSamplesWithInfo
                )
            )
        }
    }

    private fun CoroutineScope.launchCollectionStep(
        capacity: Int = Channel.RENDEZVOUS
    ) = produce(
        capacity = capacity,
    ) {
        _collectionStatus.update { CollectionProcessStatus.Initializing }
        val missingDependencies = collectors.parMapNotNull { collector ->
            collector.checkDependencies().toNonEmptyListOrNull()?.let { collector to it }
        }.associate { it }
        if (missingDependencies.isNotEmpty()) {
            _collectionStatus.update { CollectionProcessStatus.MissingDependencies(missingDependencies) }
            return@produce
        }
        _collectionStatus.update {
            CollectionProcessStatus.Collecting(
                CollectionProcessStatus.CollectionStats(
                    collectorsStatus = collectors.associateWith { null },
                    samplesCollectedByCollector = collectors.associateWith { 0 },
                    totalSamplesCollected = 0,
                    samplesWithInfoByProvider = infoProviders.associateWith { 0 },
                    totalSamplesWithInfo = 0
                )
            )
        }

        collectors
            .map { collector ->
                val collectorWorkingDirectory = cacheDir.resolve(collector.name)
                collectorWorkingDirectory.createDirectories()
                collector.start(
                    Collector.CollectionParameters(
                        workingDirectory = collectorWorkingDirectory
                    )
                ).map { collector to it }
            }
            .merge()
            .onEach { (collector, status) ->
                updateStatusForCollector(collector, status)
            }
            .parMap { (collector, status) ->
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
            .collect { (sample, source, hashes) ->
                val sampleFile = samplesDir.resolve(hashes[HashAlgorithms.SHA1]!!)
                val entityAdded = newSuspendedTransaction(context = Dispatchers.IO, db = auriDB) {
                    RawSampleEntity.find {
                        RawSampleTable.sha256 eq hashes[HashAlgorithms.SHA256]!!
                    }.firstOrNull()?.let {
                        Logger.i { "Sample ${sample.name} already exists in the database, skipping" }
                        return@newSuspendedTransaction null
                    }
                    val savedEntity = RawSampleEntity.new {
                        this.md5 = hashes[HashAlgorithms.MD5]!!
                        this.sha1 = hashes[HashAlgorithms.SHA1]!!
                        this.sha256 = hashes[HashAlgorithms.SHA256]!!
                        this.name = sample.name
                        this.sourceName = source.name
                        this.sourceVersion = source.version
                        this.path = sampleFile.relativeTo(samplesDir).pathString
                        this.collectionDate = LocalDate.now().toKotlinLocalDate()
                        this.submissionDate = sample.submissionDate
                    }
                    Logger.i { "Sample ${sample.name} saved to the database with ID ${savedEntity.id}" }
                    savedEntity
                }
                if (entityAdded == null) return@collect
                sample.executable.copyTo(sampleFile)
                _collectionStatus.update { currentStatus ->
                    (currentStatus as? CollectionProcessStatus.Collecting)
                        ?.let {
                            it.copy(
                                collectionStats = it.collectionStats.copy(
                                    samplesCollectedByCollector = it.collectionStats.samplesCollectedByCollector.toMutableMap()
                                        .apply {
                                            this[source] = (this[source] ?: 0) + 1
                                        },
                                    totalSamplesCollected = it.collectionStats.totalSamplesCollected + 1
                                )
                            )
                        } ?: currentStatus
                }
                send(entityAdded)
            }
    }

    private fun CoroutineScope.launchInfoProviderStep(
        collectionFlow: Flow<RawSampleEntity>
    ) = launch {
        collectionFlow.collect { collectedSample ->
            infoProviders.asFlow().withIndex().parMap { (priority, infoProvider) ->
                val sampleInfo = infoProvider.sampleInfoByHash {
                    when (it) {
                        HashAlgorithms.MD5 -> collectedSample.md5
                        HashAlgorithms.SHA1 -> collectedSample.sha1
                        HashAlgorithms.SHA256 -> collectedSample.sha256
                    }
                } ?: return@parMap null
                object {
                    val priority = priority
                    val infoProvider = infoProvider
                    val sampleInfo = sampleInfo
                }
            }.filterNotNull()
                .collect { data ->
                    newSuspendedTransaction(context = Dispatchers.IO, db = auriDB) {
                        SampleInfoEntity.new(
                            SampleInfoEntity.id(
                                sampleId = collectedSample.id.value,
                                sourceName = data.infoProvider.name
                            )
                        ) {
                            this.hashMatched = data.sampleInfo.hashMatched
                            this.malwareFamily = data.sampleInfo.malwareFamily
                            this.extraInfo = data.sampleInfo.extraInfo
                            this.fetchDate = LocalDate.now().toKotlinLocalDate()
                            this.priority = data.priority
                        }
                    }
                    _collectionStatus.update { currentStatus ->
                        (currentStatus as? CollectionProcessStatus.Collecting)
                            ?.let {
                                it.copy(
                                    collectionStats = it.collectionStats.copy(
                                        samplesWithInfoByProvider = it.collectionStats.samplesWithInfoByProvider.toMutableMap()
                                            .apply {
                                                this[data.infoProvider] = (this[data.infoProvider] ?: 0) + 1
                                            },
                                        totalSamplesWithInfo = it.collectionStats.totalSamplesWithInfo + 1
                                    )
                                )
                            } ?: currentStatus
                    }
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
                    collectionStats = it.collectionStats.copy(
                        it.collectionStats.collectorsStatus.toMutableMap().apply {
                            this[collector] = status
                        }
                    )
                )
            }
            ?: currentStatus
    }

    private fun RawCollectedSample.hasValidFile(): Boolean {
        if (!executable.exists()) return false
        if (!executable.isRegularFile()) return false
        if (!executable.isReadable()) return false
        return true
    }

    private data class SampleWithSourceAndHashes(
        val sample: RawCollectedSample,
        val source: Collector,
        val hashes: Map<HashAlgorithms, String>
    )
}