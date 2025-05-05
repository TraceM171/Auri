package com.auri.cli

import com.auri.app.evaluation.EvaluationProcessStatus
import com.auri.app.launchEvaluationAnalysis
import com.auri.cli.common.*
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.rendering.*
import com.github.ajalt.mordant.table.*
import com.github.ajalt.mordant.widgets.Text
import com.github.ajalt.mordant.widgets.definitionList
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class Evaluation : SuspendingCliktCommand(name = "evaluation") {
    private val phaseDescription = """
        The Evaluation Phase is the third phase of the AURI pipeline. It is responsible for checking the effectiveness of security vendors against the live samples.
    """.trimIndent()

    override fun help(context: Context): String = phaseDescription

    private val baseDirectory: Path by baseDirectory()
    private val runbook: Path by runbook()
    private val pruneCache: Boolean by pruneCache()
    private val verbosity: Int by verbosity()
    private val streamed: Boolean by streamed()

    override suspend fun run(): Unit = coroutineScope {
        val evaluationProcessStatus = launchEvaluationAnalysis(
            baseDirectory = baseDirectory,
            runbook = runbook,
            pruneCache = pruneCache,
            minLogSeverity = verbosityToSeverity(verbosity),
            streamed = streamed
        )
        terminal.baseAuriTui(
            phaseTitle = evaluationPhaseTitle(),
            phaseDescription = phaseDescription,
            phaseData = evaluationProcessStatus,
            phaseTui = VerticalLayoutBuilder::tui
        )
    }
}

private fun VerticalLayoutBuilder.tui(
    processStatus: EvaluationProcessStatus
) {
    when (processStatus) {
        EvaluationProcessStatus.NotStarted -> {
            cell("Not started") {
                style(bold = true)
            }
            cell(Text("The evaluation analysis will begin shortly", whitespace = Whitespace.PRE_WRAP))
        }

        EvaluationProcessStatus.Initializing -> {
            cell("Initializing") {
                style(bold = true)
            }
            cell(Text("Performing evaluation analysis initialization tasks", whitespace = Whitespace.PRE_WRAP))
        }

        is EvaluationProcessStatus.CapturingGoodState -> {
            cell(Text("Capturing good state", whitespace = Whitespace.PRE_WRAP)) {
                style(bold = true)
            }
            cell(
                Text(
                    """
                        The evaluation analysis is capturing the good state of the system, this is done by running the analyzers on a fresh virtual machine snapshot, without any malware.
                    """.trimIndent(),
                    whitespace = Whitespace.PRE_WRAP
                )
            )
            val subStepText = when (val subStep = processStatus.step) {
                EvaluationProcessStatus.CapturingGoodState.Step.StartingVM -> "Starting VM"
                is EvaluationProcessStatus.CapturingGoodState.Step.Capturing -> "Capturing for ${subStep.analyzer.name}"
                EvaluationProcessStatus.CapturingGoodState.Step.StoppingVM -> "Stopping VM"
            }
            cell("")
            cell(Text(subStepText, whitespace = Whitespace.PRE_WRAP)) {
                style(italic = true)
            }
            cell("")
        }

        is EvaluationProcessStatus.Analyzing -> {
            cell("Analyzing") {
                style(bold = true)
            }
            cell(
                Text(
                    """
                        The samples are being analyzed and its status is shown in the table bellow.
                        If streaming is enabled, the evaluation analysis will continue indefinitely, in this case the phase can be ended by pressing [^C] when the user desires, results are saved on receive, so none will be lost by ending forcefully.
                    """.trimIndent(),
                    whitespace = Whitespace.PRE_WRAP
                )
            )
            processStatus.runningNow?.let { runningNow ->
                val (_, subStepName) = when (val runningNowStep = runningNow.step) {
                    EvaluationProcessStatus.Analyzing.RunningNow.Step.StartingVM ->
                        1 to "Starting VM"

                    EvaluationProcessStatus.Analyzing.RunningNow.Step.SendingSample ->
                        2 to "Sending sample to VM"

                    EvaluationProcessStatus.Analyzing.RunningNow.Step.LaunchingSampleProcess ->
                        3 to "Launching sample process in VM"

                    is EvaluationProcessStatus.Analyzing.RunningNow.Step.WaitingChanges ->
                        4 to "Waiting for changes in VM (timeout in ${(runningNowStep.sampleTimeout - Clock.System.now()).inWholeSeconds.seconds})"

                    EvaluationProcessStatus.Analyzing.RunningNow.Step.SavingResults ->
                        5 to "Saving results"

                    EvaluationProcessStatus.Analyzing.RunningNow.Step.StoppingVM ->
                        6 to "Stopping VM"
                }
                cell("")
                cell(
                    Text("Sample ${runningNow.sampleId}", whitespace = Whitespace.PRE_WRAP)
                )
                cell(
                    grid {
                        column(2) {
                            this.width = ColumnWidth(priority = 1)
                        }
                        row {
                            cell(Text(runningNow.vendor.name, whitespace = Whitespace.PRE_WRAP)) {
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

        is EvaluationProcessStatus.Finished -> {
            cell("Finished") {
                style(bold = true)
            }
            cell(
                Text(
                    """
                        The evaluation analysis has finished because all the samples have been analyzed and streaming is not enabled.
                        All results have been saved.
                    """.trimIndent(),
                    whitespace = Whitespace.PRE_WRAP
                )
            )
        }

        is EvaluationProcessStatus.MissingDependencies -> {
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

        is EvaluationProcessStatus.Failed -> {
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
        val evaluationStats = when (processStatus) {
            is EvaluationProcessStatus.Analyzing -> processStatus.evaluationStats
            is EvaluationProcessStatus.Finished -> processStatus.evaluationStats
            is EvaluationProcessStatus.Failed,
            is EvaluationProcessStatus.CapturingGoodState,
            EvaluationProcessStatus.Initializing,
            is EvaluationProcessStatus.MissingDependencies,
            EvaluationProcessStatus.NotStarted -> return@run
        }
        cell("")
        evaluationStatsTui(evaluationStats)
        cell("")
    }
}

private fun VerticalLayoutBuilder.evaluationStatsTui(evaluationStats: EvaluationProcessStatus.EvaluationStats) {
    cell(Text("Evaluated samples"))
    cell(Text("${evaluationStats.totalSamplesAnalyzed} (${evaluationStats.totalSamples} samples left)"))
    cell("")
    cell(table {
        borderType = BorderType.SQUARE_DOUBLE_SECTION_SEPARATOR
        tableBorders = Borders.ALL
        header {
            style = TextStyle(bold = true)
            row("Vendor", "Block rate")
        }
        body {
            evaluationStats.vendorStats.forEach { (vendor, stats) ->
                val blockRate = stats.blockedSamples / stats.analyzedSamples.toDouble()
                val blockRateText = if (blockRate.isNaN()) "" else " (%.2f%)".format(blockRate * 100)
                row {
                    cell(Text(vendor.name, whitespace = Whitespace.PRE_WRAP))
                    cell(Text("${stats.blockedSamples} of ${stats.analyzedSamples}$blockRateText")) {
                        align = TextAlign.CENTER
                    }
                }
            }
        }
    })
}

private fun evaluationPhaseTitle() =
    """
____ _  _ ____ _    _  _ ____ ___ _ ____ _  _ 
|___ |  | |__| |    |  | |__|  |  | |  | |\ | 
|___  \/  |  | |___ |__| |  |  |  | |__| | \|
"""