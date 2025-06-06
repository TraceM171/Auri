package com.auri.extensions.collection

import com.auri.core.collection.Collector
import com.auri.core.collection.CollectorStatus
import com.auri.core.collection.CollectorStatus.*
import com.auri.core.collection.RawCollectedSample
import com.auri.core.common.util.HashAlgorithms
import com.auri.core.common.util.PeriodicActionConfig
import com.auri.extensions.collection.common.periodicCollection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinInstant
import kotlinx.datetime.toLocalDateTime
import java.nio.file.Path
import kotlin.io.path.*

class CustomFolderCollector(
    private val definition: Definition
) : Collector {
    override val name: String = definition.customName
    override val description: String = """
        Collect samples from a local folder.
    """.trimIndent()
    override val version: String = "0.0.1"

    data class Definition(
        val periodicity: PeriodicActionConfig? = null,
        val samplesDir: Path,
        val customName: String = "Custom folder (${samplesDir.name})",
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start(
        workingDirectory: Path,
        checkSampleExistence: suspend (HashAlgorithms, String) -> Boolean
    ): Flow<CollectorStatus> = periodicCollection(
        periodicity = definition.periodicity,
        singleCollection = { singleSamples() },
    )

    private fun singleSamples(): Flow<CollectorStatus> = flow {
        emit(Processing(what = "List of samples in folder"))
        if (!definition.samplesDir.exists()) return@flow
        if (!definition.samplesDir.isDirectory()) {
            emit(Failed(what = "list directory files", why = "File is not a directory"))
            return@flow
        }
        definition.samplesDir.listDirectoryEntries()
            .asSequence()
            .filter { it.isRegularFile() }
            .map {
                val submissionDate = it.getLastModifiedTime().toInstant().toKotlinInstant()
                    .toLocalDateTime(TimeZone.UTC).date
                RawCollectedSample(
                    submissionDate = submissionDate,
                    name = it.nameWithoutExtension,
                    executable = it,
                )
            }.map(::NewSample)
            .forEach { emit(it) }
    }
}
