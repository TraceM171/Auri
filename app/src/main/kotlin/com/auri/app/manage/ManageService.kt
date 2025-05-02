package com.auri.app.manage

import co.touchlab.kermit.Logger
import com.auri.app.common.data.entity.RawSampleEntity
import com.auri.app.common.data.entity.RawSampleTable
import com.auri.app.common.data.entity.SampleLivenessCheckTable
import com.auri.app.common.data.getAsFlow
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.nio.file.Path
import kotlin.io.path.*

internal class ManageService(
    private val samplesDir: Path,
    private val auriDB: Database,
) {
    @OptIn(ExperimentalPathApi::class)
    suspend fun pruneDeadSamples(
        aggressive: Boolean,
    ): PruneDeadSamplesResult {
        Logger.i { "Pruning dead samples..." }

        val result = when (aggressive) {
            true -> pruneNonAliveSamples()
            false -> pruneOnlyDeadSamples()
        }
        Logger.i { "Deleted ${result.prunedSamples} samples, freed ${result.bytesFreed} bytes" }
        Logger.i { "Pruning dead samples finished" }

        return result
    }

    private suspend fun pruneOnlyDeadSamples(): PruneDeadSamplesResult {
        var deletedSamples = 0
        var deletedSize = 0L

        newSuspendedTransaction(db = auriDB) {
            RawSampleEntity.getAsFlow(
                database = auriDB,
                keepListening = null,
                filter = {
                    notExists(
                        SampleLivenessCheckTable.selectAll()
                            .where { SampleLivenessCheckTable.sampleId eq RawSampleTable.id }
                            .andWhere { SampleLivenessCheckTable.isAlive eq true }
                            .limit(1)
                    )
                }
            ).collect { sample ->
                val sampleId = sample.id.value
                val samplePath = samplesDir.resolve(sample.path)

                if (samplePath.notExists()) {
                    Logger.d { "Sample $sampleId not found in $samplesDir, skipping..." }
                    return@collect
                }
                if (!samplePath.isRegularFile()) {
                    Logger.d { "Sample $sampleId is not a regular file, skipping..." }
                    return@collect
                }

                Logger.d { "Deleting sample $sampleId" }
                deletedSamples++
                deletedSize += samplePath.fileSize()
                samplePath.deleteExisting()
            }
        }

        return PruneDeadSamplesResult(
            prunedSamples = deletedSamples,
            bytesFreed = deletedSize,
        )
    }

    @OptIn(ExperimentalPathApi::class)
    private suspend fun pruneNonAliveSamples(): PruneDeadSamplesResult {
        val aliveSamplesPath = samplesDir.resolve("alive").createDirectories()

        return newSuspendedTransaction(db = auriDB) {
            RawSampleEntity.getAsFlow(
                database = auriDB,
                keepListening = null,
                filter = {
                    exists(
                        SampleLivenessCheckTable.selectAll()
                            .where { SampleLivenessCheckTable.sampleId eq RawSampleTable.id }
                            .andWhere { SampleLivenessCheckTable.isAlive eq true }
                            .limit(1)
                    )
                }
            ).collect { sample ->
                val sampleId = sample.id.value
                val samplePath = samplesDir.resolve(sample.path)

                if (samplePath.notExists()) {
                    Logger.d { "Sample $sampleId not found in $samplesDir, skipping..." }
                    return@collect
                }

                samplePath.moveTo(aliveSamplesPath.resolve(sample.path))
            }

            val aliveSamples = aliveSamplesPath.listDirectoryEntries().size
            Logger.i { "Found $aliveSamples alive samples" }

            val deadSamples = samplesDir.listDirectoryEntries().filter { it != aliveSamplesPath }
            Logger.i { "Found ${deadSamples.size} non-alive files to delete" }

            val deletedSamples = deadSamples.size
            val deletedSize = deadSamples.sumOf(Path::fileSize)

            deadSamples.forEach { sample ->
                Logger.d { "Deleting sample $sample" }
                sample.deleteRecursively()
            }

            Logger.i { "Restoring alive samples" }
            aliveSamplesPath
                .listDirectoryEntries()
                .forEach { it.moveTo(samplesDir.resolve(it.fileName)) }

            aliveSamplesPath.deleteExisting()

            PruneDeadSamplesResult(
                prunedSamples = deletedSamples,
                bytesFreed = deletedSize,
            )
        }
    }
}

