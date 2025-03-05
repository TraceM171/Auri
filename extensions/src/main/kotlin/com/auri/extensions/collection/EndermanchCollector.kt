package com.auri.extensions.collection

import arrow.core.getOrElse
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

class EndermanchCollector(
    private val definition: Definition = Definition()
) : Collector {
    override val name: String = definition.customName
    override val description: String = "Collect malware samples from the Endermanch/MalwareDatabase repository"
    override val version: String = "0.0.1"

    data class Definition(
        val customName: String = "Endermanch-MalwareDatabase",
        val periodicity: PeriodicActionConfig? = PeriodicActionConfig(
            performEvery = 1.days,
            maxRetriesPerPerform = 3,
            skipPerformIfFailed = true,
            retryEvery = 5.minutes
        ),
        val gitRepo: GitRepo = GitRepo(
            url = URI.create("https://github.com/Endermanch/MalwareDatabase.git").toURL(),
            branch = "master",
            commit = null
        ),
        val samplesFolderFilter: List<String> = listOf("ransomwares"),
        val samplesPassword: String = "mysubsarethebest",
        val samplesMagicNumberFilter: List<MagicNumber> = MagicNumber.entries,
    )

    override suspend fun checkDependencies(): List<MissingDependency> = buildList {
        DependencyChecks.checkGitAvailable(
            neededTo = "clone the source repository",
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
            .asSequence()
            .filter { it.isDirectory }
            .filterNot { it.name.startsWith(".") }
            .filter { it.name in definition.samplesFolderFilter }
            .flatMap { it.listFiles { _, name -> name.endsWith(".zip") }?.toList().orEmpty() }
            .associateWith {
                extractSamples(it)
            }

        val rawSamples = extractedSamples.mapNotNull { (sample, files) ->
            files.map {
                RawCollectedSample(
                    name = sample.nameWithoutExtension,
                    submissionDate = null,
                    executable = it
                )
            }
        }.flatten()
            .asFlow()
            .map { NewSample(it) }

        emitAll(rawSamples)
    }

    private fun extractSamples(
        zipFile: File,
    ): List<File> {
        Logger.d { "Extracting ${zipFile.nameWithoutExtension} with password ${definition.samplesPassword}" }
        val destinationDir = File(zipFile.parent, zipFile.nameWithoutExtension)
        zipFile.unzip(destinationDirectory = destinationDir, password = definition.samplesPassword)
        Logger.d { "Successfully extracted ${zipFile.name}" }
        Logger.d { "Searching for extracted executables" }
        val executables = destinationDir.walkTopDown()
            .filter { file -> file.magicNumber() in definition.samplesMagicNumberFilter }
            .toList()
        if (executables.isEmpty())
            Logger.d { "No executable found for sample ${zipFile.nameWithoutExtension}" }
        else
            Logger.d { "Found ${executables.size} executables for sample ${zipFile.nameWithoutExtension}" }
        return executables
    }
}