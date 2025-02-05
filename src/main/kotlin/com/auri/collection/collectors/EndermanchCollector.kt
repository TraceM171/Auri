package com.auri.collection.collectors

import arrow.core.getOrElse
import co.touchlab.kermit.Logger
import com.auri.collection.common.GitRepo
import com.auri.collection.common.cloneRepo
import com.auri.collection.definition.Collector
import com.auri.collection.definition.RawCollectedSample
import com.auri.core.util.MagicNumber
import com.auri.core.util.magicNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import net.lingala.zip4j.ZipFile
import java.io.File
import java.net.URI

class EndermanchCollector(
    private val definition: Definition = Definition()
) : Collector {
    override val name: String = definition.customName
    override val description: String = "Collect malware samples from the Endermanch/MalwareDatabase repository"
    override val version: String = "0.0.1"

    data class Definition(
        val customName: String = "Endermanch-MalwareDatabase",
        val gitRepo: GitRepo = GitRepo(
            url = URI.create("https://github.com/Endermanch/MalwareDatabase.git").toURL(),
            branch = "master"
        ),
        val samplesFolderFilter: List<String> = listOf("ransomwares"),
        val samplesPassword: String = "mysubsarethebest",
        val samplesMagicNumberFilter: List<MagicNumber> = MagicNumber.entries,
    )


    override fun samples(
        collectionParameters: Collector.CollectionParameters
    ): Flow<RawCollectedSample> = flow {
        val repoFolder = cloneRepo(
            gitRepo = definition.gitRepo,
            collectionParameters = collectionParameters,
        ).getOrElse { return@flow }

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

        emitAll(rawSamples.asFlow())
    }

    private fun extractSamples(
        zipFile: File,
    ): List<File> {
        Logger.d { "Extracting ${zipFile.nameWithoutExtension} with password ${definition.samplesPassword}" }
        val destinationDir = File(zipFile.parent, zipFile.nameWithoutExtension)
        ZipFile(zipFile, definition.samplesPassword.toCharArray())
            .extractAll(destinationDir.absolutePath)
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