package com.auri.collection

import arrow.core.toNonEmptyListOrNull
import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.parMapNotNull
import co.touchlab.kermit.Logger
import com.auri.common.data.entity.RawSampleEntity
import com.auri.common.data.entity.RawSampleTable
import com.auri.common.data.entity.SampleInfoEntity
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
import java.io.File

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CollectionService(
    private val cacheDir: File,
    private val samplesDir: File,
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
            val collectingOrNull = it as? CollectionProcessStatus.Collecting
            CollectionProcessStatus.Finished(
                collectorsStatus = collectingOrNull?.collectorsStatus ?: emptyMap(),
                samplesCollectedByCollector = collectingOrNull?.samplesCollectedByCollector
                    ?: emptyMap(),
                totalSamplesCollected = collectingOrNull?.totalSamplesCollected ?: 0,
                samplesWithInfoByProvider = collectingOrNull?.samplesWithInfoByProvider ?: emptyMap(),
                totalSamplesWithInfo = collectingOrNull?.totalSamplesWithInfo ?: 0
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
                collectorsStatus = collectors.associateWith { null },
                samplesCollectedByCollector = collectors.associateWith { 0 },
                totalSamplesCollected = 0,
                samplesWithInfoByProvider = infoProviders.associateWith { 0 },
                totalSamplesWithInfo = 0
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
            .collect { (sample, source, hashes) ->
                val sampleFile = File(samplesDir, hashes[HashAlgorithms.SHA1]!!)
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
                        this.path = sampleFile.relativeTo(samplesDir).path
                        this.collectionDate = java.time.LocalDate.now().toKotlinLocalDate()
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
                                samplesCollectedByCollector = it.samplesCollectedByCollector.toMutableMap().apply {
                                    this[source] = (this[source] ?: 0) + 1
                                },
                                totalSamplesCollected = it.totalSamplesCollected + 1
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
                            this.fetchDate = java.time.LocalDate.now().toKotlinLocalDate()
                            this.priority = data.priority
                        }
                    }
                    _collectionStatus.update { currentStatus ->
                        (currentStatus as? CollectionProcessStatus.Collecting)
                            ?.let {
                                it.copy(
                                    samplesWithInfoByProvider = it.samplesWithInfoByProvider.toMutableMap().apply {
                                        this[data.infoProvider] = (this[data.infoProvider] ?: 0) + 1
                                    },
                                    totalSamplesWithInfo = it.totalSamplesWithInfo + 1
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