package com.auri

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.auri.app.collectSamples
import com.auri.core.common.util.getResource
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(): Unit = runBlocking {
    val workingDirectory = File("/home/auri/TFM/auri/scratch")
    val auriDB = File(workingDirectory, "auri.db")
    val configFile = File(workingDirectory, "config.yml")

    Logger.setMinSeverity(Severity.Info)

    auriDB.delete()
    auriDB.outputStream().use { output ->
        getResource("auri.db")?.use { input ->
            input.copyTo(output)
        } ?: throw IllegalStateException("Could not find auri.db in resources")
    }

    collectSamples(
        configFile = configFile
    )

    /*val config = ConfigLoaderBuilder.default()
        .addFileSource(configFile)
        .withExplicitSealedTypes()
        .build()
        .loadConfigOrThrow<MainConf>()*/

    /*val collector = TheZooCollector(
        workingDirectory = workingDirectory,
        invalidateCache = false,
        samplesTypeFilter = Regex("ransomware"),
        samplesArchFilter = Regex("x86|x64"),
        samplesPlatformFilter = Regex("win32|win64"),
    )
    val collectionService = CollectionService(
        auriDB = File(workingDirectory, "auri.db").let(::sqliteConnection),
        collectors = listOf(collector),
    )
    collectionService.startCollection()*/
}

/*
private suspend fun testCollector(collector: Collector) {
    val samples = collector.samples().onEach {
        Logger.i { "Sample: $it" }
    }.toList()
    Logger.i { "Collected ${samples.size} samples" }
    Logger.i { "Count of samples with date: ${samples.count { it.submissionDate != null }}" }
}*/
