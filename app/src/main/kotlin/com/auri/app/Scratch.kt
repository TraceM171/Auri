package com.auri.app

import co.touchlab.kermit.Severity
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(): Unit = runBlocking {
    val baseDirectory = File("/home/auri/TFM/auri/scratch/.auri")
    val runbook = File("/home/auri/TFM/auri/scratch/runbook.yml")

    launchSampleCollection(
        baseDirectory = baseDirectory,
        runbook = runbook,
        pruneCache = false,
        minLogSeverity = Severity.Debug
    ).collect {
        println(it)
    }
}
