package com.auri

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.auri.collection.collectors.TheZooCollector
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File

fun main() = runBlocking {
    Logger.setMinSeverity(Severity.Info)

    val collector = TheZooCollector(
        workingDirectory = File("/home/auri/TFM/Scratch"),
        invalidateCache = false,
        samplesTypeFilter = Regex("ransomware"),
        samplesArchFilter = Regex("x86|x64"),
        samplesPlatformFilter = Regex("win32|win64"),
    )
    val samples = collector.samples().onEach {
        Logger.i { "Sample: $it" }
    }.toList()
    Logger.i { "Collected ${samples.size} samples" }
    Logger.i { "Count of samples with date: ${samples.count { it.submissionDate != null }}" }
}