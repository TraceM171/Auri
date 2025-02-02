package com.auri.collection.collectors

import arrow.core.getOrElse
import arrow.core.mapValuesNotNull
import co.touchlab.kermit.Logger
import com.auri.collection.common.GitRepo
import com.auri.collection.common.cloneRepo
import com.auri.collection.definition.Collector
import com.auri.collection.definition.RawCollectedSample
import com.auri.core.*
import com.auri.core.util.*
import kotlinx.coroutines.flow.*
import net.lingala.zip4j.ZipFile
import java.io.File
import java.net.URI

class Kh4sh3iCollector(
    private val definition: Definition = Definition()
) : Collector {
    override val name: String = definition.customName
    override val description: String = "Collect malware samples from the Kh4sh3i/Ransomware-Samples repository"
    override val version: String = "0.0.1"

    data class Definition(
        val customName: String = "Kh4sh3i-RansomwareSamples",
        val gitRepo: GitRepo = GitRepo(
            url = URI.create("https://github.com/kh4sh3i/Ransomware-Samples.git").toURL(),
            branch = "main"
        ),
        val samplesPassword: String = "infected",
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

        emitAll(rawSamples.asFlow())
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
        ZipFile(zipFile, definition.samplesPassword.toCharArray())
            .extractAll(sampleDir.absolutePath)
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