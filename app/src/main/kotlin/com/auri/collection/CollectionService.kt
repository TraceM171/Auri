package com.auri.collection

import arrow.fx.coroutines.parMapNotNullUnordered
import co.touchlab.kermit.Logger
import com.auri.core.collection.model.Collector
import com.auri.core.collection.model.RawCollectedSample
import com.auri.core.common.util.HashAlgorithms
import com.auri.core.common.util.hashes
import com.auri.core.data.entity.RawSampleEntity
import com.auri.core.data.entity.RawSampleTable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    suspend fun startCollection() = coroutineScope {
        collectors
            .map { collector ->
                collector.samples(
                    Collector.CollectionParameters(
                        workingDirectory = File(workingDirectory, collector.name).apply { mkdirs() },
                        invalidateCache = invalidateCache
                    )
                ).map { SampleWithSource(it, collector) }
            }
            .merge()
            .parMapNotNullUnordered {
                if (!it.sample.hasValidFile()) {
                    Logger.w { "Invalid file for sample ${it.sample.name}, emitted by ${it.source.name}: ${it.sample.executable}" }
                    return@parMapNotNullUnordered null
                }
                val hashes = it.sample.executable.hashes(
                    HashAlgorithms.MD5, HashAlgorithms.SHA1, HashAlgorithms.SHA256
                )
                SampleWithSourceAndHashes(it.sample, it.source, hashes)
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
            }
    }

    private fun RawCollectedSample.hasValidFile(): Boolean {
        if (!executable.exists()) return false
        if (!executable.isFile) return false
        if (!executable.canRead()) return false
        return true
    }

    private data class SampleWithSource(
        val sample: RawCollectedSample,
        val source: Collector
    )

    private data class SampleWithSourceAndHashes(
        val sample: RawCollectedSample,
        val source: Collector,
        val hashes: Map<HashAlgorithms, String>
    )
}