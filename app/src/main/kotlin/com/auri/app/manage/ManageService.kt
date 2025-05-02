package com.auri.app.manage

import co.touchlab.kermit.Logger
import com.auri.app.common.data.entity.RawSampleEntity
import com.auri.app.common.data.entity.RawSampleTable
import com.auri.app.common.data.entity.SampleLivenessCheckTable
import com.auri.app.common.data.getAsFlow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.nio.file.Path
import kotlin.io.path.*

internal class ManageService(
    private val samplesDir: Path,
    private val auriDB: Database,
) {
    @OptIn(ExperimentalPathApi::class)
    suspend fun pruneDeadSamples(): PruneDeadSamplesResult {
        Logger.i { "Pruning dead samples..." }

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

            aliveSamplesPath.deleteExisting()

            Logger.i { "Deleted $deletedSamples samples, freed ${deletedSize / 1024} KB" }
            Logger.i { "Restoring alive samples" }
            aliveSamplesPath
                .listDirectoryEntries()
                .forEach { it.moveTo(samplesDir.resolve(it.fileName)) }

            PruneDeadSamplesResult(
                prunedSamples = deletedSamples,
                bytesFreed = deletedSize,
            )
        }
    }
}

