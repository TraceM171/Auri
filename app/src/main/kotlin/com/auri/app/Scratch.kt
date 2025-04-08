package com.auri.app

import co.touchlab.kermit.Severity
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(): Unit = runBlocking {
    val baseDirectory = File("/home/auri/TFM/auri/scratch/.auri")
//    val auriDB = File(baseDirectory, "auri.db").let(::sqliteConnection)

    launchSampleAnalysis(
        baseDirectory = baseDirectory,
        runbook = File("/home/auri/TFM/auri/scratch/runbook.yml"),
        minLogSeverity = Severity.Debug,
        pruneCache = true,
        streamed = false
    ).collect { status ->
        println(status)
    }
}
