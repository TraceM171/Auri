package com.auri

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.auri.collection.CollectionService
import com.auri.collection.Collector
import com.auri.collection.collectors.TheZooCollector
import com.auri.core.data.sqliteConnection
import com.auri.core.util.getResource
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File

fun main() = runBlocking {
    val workingDirectory = File("/home/auri/TFM/Scratch")
    val auriDB = File(workingDirectory, "auri.db")
    Logger.setMinSeverity(Severity.Info)

    auriDB.delete()
    auriDB.outputStream().use { output ->
        getResource("auri.db")?.use { input ->
            input.copyTo(output)
        } ?: throw IllegalStateException("Could not find auri.db in resources")
    }

    val collector = TheZooCollector(
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
    collectionService.startCollection()
}

private suspend fun testCollector(collector: Collector) {
    val samples = collector.samples().onEach {
        Logger.i { "Sample: $it" }
    }.toList()
    Logger.i { "Collected ${samples.size} samples" }
    Logger.i { "Count of samples with date: ${samples.count { it.submissionDate != null }}" }
}