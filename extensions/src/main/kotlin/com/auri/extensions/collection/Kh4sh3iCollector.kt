package com.auri.extensions.collection

import arrow.core.getOrElse
import arrow.core.mapValuesNotNull
import co.touchlab.kermit.Logger
import com.auri.core.collection.Collector
import com.auri.core.collection.CollectorStatus
import com.auri.core.collection.CollectorStatus.*
import com.auri.core.collection.RawCollectedSample
import com.auri.core.common.MissingDependency
import com.auri.core.common.util.*
import com.auri.extensions.collection.common.periodicCollection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.io.File
import java.net.URI
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class Kh4sh3iCollector(
    private val definition: Definition = Definition()
) : Collector {
    override val name: String = definition.customName
    override val description: String = "Collect malware samples from the Kh4sh3i/Ransomware-Samples repository"
    override val version: String = "0.0.1"

    data class Definition(
        val customName: String = "Kh4sh3i-RansomwareSamples",
        val periodicity: PeriodicActionConfig? = PeriodicActionConfig(
            performEvery = 1.days,
            maxRetriesPerPerform = 3,
            skipPerformIfFailed = true,
            retryEvery = 5.minutes
        ),
        val gitRepo: GitRepo = GitRepo(
            url = URI.create("https://github.com/kh4sh3i/Ransomware-Samples.git").toURL(),
            branch = "main",
            commit = null
        ),
        val samplesPassword: String = "infected",
        val samplesMagicNumberFilter: List<MagicNumber> = MagicNumber.entries,
    )

    override suspend fun checkDependencies(): List<MissingDependency> = buildList {
        DependencyChecks.checkGitAvailable(
            use = "cloning the source repository",
        )?.let(::add)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start(
        collectionParameters: Collector.CollectionParameters
    ): Flow<CollectorStatus> = periodicCollection(
        periodicity = definition.periodicity,
        collectionParameters = collectionParameters,
        singleCollection = ::singleSamples
    )

    private fun singleSamples(
        collectionParameters: Collector.CollectionParameters
    ): Flow<CollectorStatus> = flow {
        emit(Downloading(what = "Repository"))
        val repoFolder = cloneRepo(
            workingDirectory = collectionParameters.workingDirectory,
            gitRepo = definition.gitRepo
        ).getOrElse {
            emit(Failed(what = "Clone repository", why = it))
            return@flow
        }

        emit(Processing(what = "Extract samples"))
        val extractedSamples = repoFolder.listFiles()!!
            .filter { it.isDirectory }
            .filterNot { it.name.startsWith(".") }
            .associateWith { sampleDir ->
                val sampleZip = sampleDir.listFiles { _, name -> name.endsWith(".zip") }?.firstOrNull()
                if (sampleZip == null) {
                    Logger.e { "No zip file found for sample ${sampleDir.name}" }
                    return@associateWith null
                }
                extractSample(sampleDir)
            }.mapValuesNotNull { it.value?.takeUnless(List<File>::isEmpty) }

        val rawSamples = extractedSamples.mapNotNull { (sample, files) ->
            files.map {
                RawCollectedSample(
                    name = sample.name,
                    submissionDate = null,
                    executable = it
                )
            }
        }.flatten()
            .asFlow()
            .map { NewSample(it) }

        emitAll(rawSamples)
    }

    private fun extractSample(
        sampleDir: File,
    ): List<File> {
        Logger.d { "Extracting sample ${sampleDir.name}" }

        val zipFile = sampleDir.listFiles { _, name -> name.endsWith(".zip") }?.firstOrNull() ?: run {
            Logger.d { "No zip file found for sample ${sampleDir.name}" }
            return emptyList()
        }
        Logger.d { "Extracting ${zipFile.name} with password ${definition.samplesPassword}" }
        zipFile.unzip(destinationDirectory = sampleDir, password = definition.samplesPassword)
        Logger.d { "Successfully extracted ${zipFile.name}" }
        Logger.d { "Searching for extracted executable" }
        val executables = sampleDir.walkTopDown()
            .filter { file -> file.magicNumber() in definition.samplesMagicNumberFilter }
            .toList()
        if (executables.isEmpty())
            Logger.d { "No executable found for sample ${sampleDir.name}" }
        else
            Logger.d { "Found ${executables.size} executables for sample ${sampleDir.name}" }
        return executables
    }
}
