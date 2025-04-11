package com.auri.app

import co.touchlab.kermit.Severity
import kotlinx.coroutines.runBlocking
import kotlin.io.path.Path

fun main(): Unit = runBlocking {
    val baseDirectory = Path("/home/auri/TFM/auri/scratch/.auri")
    val runbook = Path("/home/auri/TFM/auri/scratch/runbook.yml")
//    val auriDB = File(baseDirectory, "auri.db").let(::sqliteConnection)

    /*launchSampleAnalysis(
        baseDirectory = baseDirectory,
        runbook = runbook,
        minLogSeverity = Severity.Debug,
        pruneCache = false,
        streamed = false
    ).collect { status ->
        println(status)
    }*/

    launchSampleCollection(
        baseDirectory = baseDirectory,
        runbook = runbook,
        minLogSeverity = Severity.Debug,
        pruneCache = false
    ).collect { status ->
        println(status)
    }
}
