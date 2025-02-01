package com.auri.collection

import arrow.fx.coroutines.parMapNotNullUnordered
import co.touchlab.kermit.Logger
import com.auri.core.data.entity.RawSampleEntity
import com.auri.core.data.entity.RawSampleTable
import com.auri.core.util.Algorithm
import com.auri.core.util.hashes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

class CollectionService(
    private val auriDB: Database,
    private val collectors: List<Collector>
) {
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    suspend fun startCollection() = coroutineScope {
        collectors
            .map { collector ->
                collector.samples()
                    .map { SampleWithSource(it, collector) }
            }
            .merge()
            .parMapNotNullUnordered {
                if (!it.sample.hasValidFile()) {
                    Logger.w { "Invalid file for sample ${it.sample.name}, emitted by ${it.source.name}: ${it.sample.executable}" }
                    return@parMapNotNullUnordered null
                }
                val hashes = it.sample.executable.hashes(
                    Algorithm.MD5, Algorithm.SHA1, Algorithm.SHA256
                )
                SampleWithSourceAndHashes(it.sample, it.source, hashes)
            }.collect { (sample, source, hashes) ->
                transaction(auriDB) {
                    RawSampleEntity.find {
                        RawSampleTable.sha256 eq hashes[Algorithm.SHA256]!!
                    }.firstOrNull()?.let {
                        Logger.i { "Sample ${sample.name} already exists in the database, skipping" }
                        return@transaction
                    }
                    val savedEntity = RawSampleEntity.new {
                        this.md5 = hashes[Algorithm.MD5]!!
                        this.sha1 = hashes[Algorithm.SHA1]!!
                        this.sha256 = hashes[Algorithm.SHA256]!!
                        this.name = sample.name
                        this.sourceName = source.name
                        this.sourceVersion = source.version
                        this.path = sample.executable.path
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
        val hashes: Map<Algorithm, String>
    )
}