package com.auri.cli

import com.auri.app.launchLivenessAnalysis
import com.auri.app.liveness.LivenessProcessStatus
import com.auri.cli.common.*
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.VerticalLayoutBuilder
import com.github.ajalt.mordant.table.grid
import com.github.ajalt.mordant.widgets.Text
import com.github.ajalt.mordant.widgets.definitionList
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class Liveness : SuspendingCliktCommand(name = "liveness") {
    private val phaseDescription = """
        The Liveness Phase is the second phase of the AURI pipeline. It is responsible for obtaining liveness status about collected samples, uses multiple sources via dynamic execution.
    """.trimIndent()

    override fun help(context: Context): String = phaseDescription

    private val baseDirectory: Path by baseDirectory()
    private val runbook: Path by runbook()
    private val pruneCache: Boolean by pruneCache()
    private val verbosity: Int by verbosity()
    private val streamed: Boolean by streamed()

    override suspend fun run(): Unit = coroutineScope {
        val livenessProcessStatus = launchLivenessAnalysis(
            baseDirectory = baseDirectory,
            runbook = runbook,
            pruneCache = pruneCache,
            minLogSeverity = verbosityToSeverity(verbosity),
            streamed = streamed
        )
        terminal.baseAuriTui(
            phaseTitle = livenessPhaseTitle(),
            phaseDescription = phaseDescription,
            phaseData = livenessProcessStatus,
            phaseTui = VerticalLayoutBuilder::tui
        )
    }
}

private fun VerticalLayoutBuilder.tui(
    processStatus: LivenessProcessStatus
) {
    when (processStatus) {
        LivenessProcessStatus.NotStarted -> {
            cell("Not started") {
                style(bold = true)
            }
            cell(Text("The liveness analysis will begin shortly", whitespace = Whitespace.PRE_WRAP))
        }

        LivenessProcessStatus.Initializing -> {
            cell("Initializing") {
                style(bold = true)
            }
            cell(Text("Performing liveness analysis initialization tasks", whitespace = Whitespace.PRE_WRAP))
        }

        is LivenessProcessStatus.CapturingGoodState -> {
            cell(Text("Capturing good state", whitespace = Whitespace.PRE_WRAP)) {
                style(bold = true)
            }
            cell(
                Text(
                    """
                        The liveness analysis is capturing the good state of the system, this is done by running the analyzers on a fresh virtual machine snapshot, without any malware.
                    """.trimIndent(),
                    whitespace = Whitespace.PRE_WRAP
                )
            )
            val subStepText = when (val subStep = processStatus.step) {
                LivenessProcessStatus.CapturingGoodState.Step.StartingVM -> "Starting VM"
                is LivenessProcessStatus.CapturingGoodState.Step.Capturing -> "Capturing for ${subStep.analyzer.name}"
                LivenessProcessStatus.CapturingGoodState.Step.StoppingVM -> "Stopping VM"
            }
            cell("")
            cell(Text(subStepText, whitespace = Whitespace.PRE_WRAP)) {
                style(italic = true)
            }
            cell("")
        }

        is LivenessProcessStatus.Analyzing -> {
            cell("Analyzing") {
                style(bold = true)
            }
            cell(
                Text(
                    """
                        The samples are being analyzed and its status is shown in the table bellow.
                        If streaming is enabled, the liveness analysis will continue indefinitely, in this case the phase can be ended by pressing [^C] when the user desires, results are saved on receive, so none will be lost by ending forcefully.
                    """.trimIndent(),
                    whitespace = Whitespace.PRE_WRAP
                )
            )
            processStatus.runningNow?.let { runningNow ->
                val (_, subStepName) = when (val runningNowStep = runningNow.step) {
                    LivenessProcessStatus.Analyzing.RunningNow.Step.StartingVM ->
                        1 to "Starting VM"

                    LivenessProcessStatus.Analyzing.RunningNow.Step.SendingSample ->
                        2 to "Sending sample to VM"

                    LivenessProcessStatus.Analyzing.RunningNow.Step.LaunchingSampleProcess ->
                        3 to "Launching sample process in VM"

                    is LivenessProcessStatus.Analyzing.RunningNow.Step.WaitingChanges ->
                        4 to "Waiting for changes in VM (timeout in ${(runningNowStep.sampleTimeout - Clock.System.now()).inWholeSeconds.seconds})"

                    LivenessProcessStatus.Analyzing.RunningNow.Step.SavingResults ->
                        5 to "Saving results"

                    LivenessProcessStatus.Analyzing.RunningNow.Step.StoppingVM ->
                        6 to "Stopping VM"
                }
                cell("")
                cell(
                    grid {
                        column(2) {
                            this.width = ColumnWidth(priority = 1)
                        }
                        row {
                            cell(Text("Sample ${runningNow.sampleId}", whitespace = Whitespace.PRE_WRAP)) {
                                style(italic = true)
                            }
                            cell(" - ")
                            cell(Text(subStepName, whitespace = Whitespace.PRE_WRAP))
                        }
                    }
                )
                cell("")
            }
        }

        is LivenessProcessStatus.Finished -> {
            cell("Finished") {
                style(bold = true)
            }
            cell(
                Text(
                    """
                        The liveness analysis has finished because all the samples have been analyzed and streaming is not enabled.
                        All results have been saved.
                    """.trimIndent(),
                    whitespace = Whitespace.PRE_WRAP
                )
            )
        }

        is LivenessProcessStatus.MissingDependencies -> {
            cell(Text("Some needed dependencies are missing", whitespace = Whitespace.PRE_WRAP)) {
                style(bold = true, color = TextColors.brightRed)
            }
            cell(
                definitionList {
                    processStatus.missingDependencies.forEach { collector ->
                        entry {
                            term("-> Analyzer ${collector.key.name}:")
                            description(definitionList {
                                inline = true
                                collector.value.forEach {
                                    entry {
                                        term("    -> Dependency '${it.name}' (${it.version ?: "any version"}):")
                                        description("Needed to ${it.neededTo}. To resolve it please ${it.resolution}")
                                    }
                                }
                            })
                        }
                    }
                }
            ) {
                style(color = TextColors.brightRed)
            }
        }

        is LivenessProcessStatus.Failed -> {
            cell("Failed") {
                style(bold = true, color = TextColors.brightRed)
            }
            cell(
                Text(
                    "An error occurred in ${processStatus.what}: ${processStatus.why}",
                    whitespace = Whitespace.PRE_WRAP
                )
            ) {
                style(color = TextColors.brightRed)
            }
        }
    }
    run {
        val livenessStats = when (processStatus) {
            is LivenessProcessStatus.Analyzing -> processStatus.livenessStats
            is LivenessProcessStatus.Finished -> processStatus.livenessStats
            is LivenessProcessStatus.Failed,
            is LivenessProcessStatus.CapturingGoodState,
            LivenessProcessStatus.Initializing,
            is LivenessProcessStatus.MissingDependencies,
            LivenessProcessStatus.NotStarted -> return@run
        }
        cell("")
        livenessStatsTui(livenessStats)
        cell("")
    }
}

private fun VerticalLayoutBuilder.livenessStatsTui(livenessStats: LivenessProcessStatus.LivenessStats) {
    val aliveSamples = livenessStats.samplesStatus.count { it.value.changeFound }
    val alivePercent =
        (aliveSamples.toFloat() / livenessStats.totalSamplesAnalyzed * 100)
            .let {
                when {
                    it.isNaN() -> 0f
                    it.isInfinite() && it > 0 -> 100f
                    it.isInfinite() && it < 0 -> 0f
                    else -> it
                }
            }
    cell(
        grid {
            column(1) {
                this.width = ColumnWidth(priority = 1)
            }
            row {
                cell(Text("Alive samples"))
                cell(Text("$aliveSamples (${"%.2f".format(alivePercent)}% of analyzed samples)"))
            }
            row {
                cell(Text("Analyzed samples"))
                cell(Text("${livenessStats.totalSamplesAnalyzed} (${livenessStats.totalSamples} samples left)"))
            }
        }
    )
}

private fun livenessPhaseTitle() =
    """
_    _ _  _ ____ _  _ ____ ____ ____ 
|    | |  | |___ |\ | |___ [__  [__  
|___ |  \/  |___ | \| |___ ___] ___]
"""