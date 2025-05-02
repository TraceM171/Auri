package com.auri.app

import arrow.continuations.SuspendApp
import co.touchlab.kermit.Severity
import kotlin.io.path.Path

fun main(): Unit = SuspendApp {
    val baseDirectory = Path("/home/auri/TFM/auri/scratch/.auri")
    val runbook = Path("/home/auri/TFM/auri/scratch/runbook.yml")

    /*launchLivenessAnalysis(
        baseDirectory = baseDirectory,
        runbook = runbook,
        minLogSeverity = Severity.Debug,
        pruneCache = false,
        streamed = true
    ).collect { status ->
        println(status)
    }*/

    /*launchSampleCollection(
        baseDirectory = baseDirectory,
        runbook = runbook,
        minLogSeverity = Severity.Debug,
        pruneCache = false
    ).collect { status ->
        println(status)
    }*/

    /*launchEvaluationAnalysis(
        baseDirectory = baseDirectory,
        runbook = runbook,
        minLogSeverity = Severity.Debug,
        pruneCache = false,
        streamed = true
    ).collect { status ->
        println(status)
    }*/

    pruneSamples(
        baseDirectory = baseDirectory,
        runbook = runbook,
        minLogSeverity = Severity.Debug,
    )
}
