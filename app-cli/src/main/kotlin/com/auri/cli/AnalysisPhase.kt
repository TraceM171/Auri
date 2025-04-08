package com.auri.cli

import com.auri.app.analysis.AnalysisProcessStatus
import com.auri.app.launchSampleAnalysis
import com.auri.cli.common.*
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.table.VerticalLayoutBuilder
import com.github.ajalt.mordant.table.grid
import com.github.ajalt.mordant.table.horizontalLayout
import com.github.ajalt.mordant.widgets.ProgressBar
import com.github.ajalt.mordant.widgets.Text
import com.github.ajalt.mordant.widgets.definitionList
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class Analysis : SuspendingCliktCommand(name = "analysis") {
    private val phaseDescription = """
        The Analysis Phase is the second phase of the AURI pipeline. It is responsible for obtaining information about collected samples, uses multiple sources and runs dynamic execution checks to also ensure liveness of samples.
    """.trimIndent()

    override fun help(context: Context): String = phaseDescription

    private val baseDirectory: Path by baseDirectory()
    private val runbook: Path by runbook()
    private val pruneCache: Boolean by pruneCache()
    private val verbosity: Int by verbosity()
    private val streamed: Boolean by streamed()

    override suspend fun run(): Unit = coroutineScope {
        val analysisProcessStatus = launchSampleAnalysis(
            baseDirectory = baseDirectory,
            runbook = runbook,
            pruneCache = pruneCache,
            minLogSeverity = verbosityToSeverity(verbosity),
            streamed = streamed
        )
        terminal.baseAuriTui(
            phaseTitle = analysisPhaseTitle(),
            phaseDescription = phaseDescription,
            phaseData = analysisProcessStatus,
            phaseTui = VerticalLayoutBuilder::tui
        )
    }
}

private fun VerticalLayoutBuilder.tui(
    processStatus: AnalysisProcessStatus
) {
    when (processStatus) {
        AnalysisProcessStatus.NotStarted -> {
            cell("Not started") {
                style(bold = true)
            }
            cell(Text("The analysis will begin shortly", whitespace = Whitespace.NORMAL))
        }

        AnalysisProcessStatus.Initializing -> {
            cell("Initializing") {
                style(bold = true)
            }
            cell(Text("Performing phase initialization tasks", whitespace = Whitespace.NORMAL))
        }

        is AnalysisProcessStatus.CapturingGoodState -> {
            cell(Text("Capturing good state", whitespace = Whitespace.NORMAL)) {
                style(bold = true)
            }
            cell(
                Text(
                    """
                        The analysis is capturing the good state of the system, this is done by running the analyzers on a fresh virtual machine snapshot, without any malware.
                    """.trimIndent(),
                    whitespace = Whitespace.NORMAL
                )
            )
            val subStepText = when (val subStep = processStatus.step) {
                AnalysisProcessStatus.CapturingGoodState.Step.StartingVM -> "Starting VM"
                is AnalysisProcessStatus.CapturingGoodState.Step.Capturing -> "Capturing for ${subStep.analyzer.name}"
                AnalysisProcessStatus.CapturingGoodState.Step.StoppingVM -> "Stopping VM"
            }
            cell("")
            cell(Text(subStepText, whitespace = Whitespace.NORMAL)) {
                style(italic = true)
            }
            cell("")
        }

        is AnalysisProcessStatus.Analyzing -> {
            cell("Analyzing") {
                style(bold = true)
            }
            cell(
                Text(
                    """
                        The samples are being analyzed and its status is shown in the table bellow.
                        If streaming is enabled, the analysis will continue indefinitely, in this case the phase can be ended by pressing [^C] when the user desires, results are saved on receive, so none will be lost by ending forcefully.
                    """.trimIndent(),
                    whitespace = Whitespace.NORMAL
                )
            )
            processStatus.runningNow?.let { runningNow ->
                val (subStepIndex, subStepName) = when (val runningNowStep = runningNow.step) {
                    AnalysisProcessStatus.Analyzing.RunningNow.Step.StartingVM ->
                        1 to "Starting VM"

                    AnalysisProcessStatus.Analyzing.RunningNow.Step.SendingSample ->
                        2 to "Sending sample to VM"

                    AnalysisProcessStatus.Analyzing.RunningNow.Step.LaunchingSampleProcess ->
                        3 to "Launching sample process in VM"

                    is AnalysisProcessStatus.Analyzing.RunningNow.Step.WaitingChanges ->
                        4 to "Waiting for changes in VM (timeout in ${(runningNowStep.sampleTimeout - Clock.System.now()).inWholeSeconds.seconds})"

                    AnalysisProcessStatus.Analyzing.RunningNow.Step.SavingResults ->
                        5 to "Saving results"

                    AnalysisProcessStatus.Analyzing.RunningNow.Step.StoppingVM ->
                        6 to "Stopping VM"
                }
                cell("")
                cell(
                    horizontalLayout {
                        cell(Text("Sample ${runningNow.sampleId}", whitespace = Whitespace.NORMAL))
                        cell("  ")
                        cell(ProgressBar(completed = subStepIndex.toLong(), total = 6L, width = 10))
                        cell(Text(subStepName, whitespace = Whitespace.NORMAL))
                    }
                )
                cell("")
            }
        }

        is AnalysisProcessStatus.Finished -> {
            cell("Finished") {
                style(bold = true)
            }
            cell(
                Text(
                    """
                        The analysis has finished because all the samples have been analyzed and streaming is not enabled.
                        All results have been saved.
                    """.trimIndent(),
                    whitespace = Whitespace.NORMAL
                )
            )
        }

        is AnalysisProcessStatus.MissingDependencies -> {
            cell(Text("Some needed dependencies are missing", whitespace = Whitespace.NORMAL)) {
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

        is AnalysisProcessStatus.Failed -> {
            cell("Failed") {
                style(bold = true, color = TextColors.brightRed)
            }
            cell(
                Text(
                    "An error occurred in ${processStatus.what}: ${processStatus.why}",
                    whitespace = Whitespace.NORMAL
                )
            ) {
                style(color = TextColors.brightRed)
            }
        }
    }
    run {
        val analysisStats = when (processStatus) {
            is AnalysisProcessStatus.Analyzing -> processStatus.analysisStats
            is AnalysisProcessStatus.Finished -> processStatus.analysisStats
            is AnalysisProcessStatus.Failed,
            is AnalysisProcessStatus.CapturingGoodState,
            AnalysisProcessStatus.Initializing,
            is AnalysisProcessStatus.MissingDependencies,
            AnalysisProcessStatus.NotStarted -> return@run
        }
        cell("")
        analysisStatsTui(analysisStats)
        cell("")
    }
}

private fun VerticalLayoutBuilder.analysisStatsTui(analysisStats: AnalysisProcessStatus.AnalysisStats) {
    val alivePercent =
        (analysisStats.samplesStatus.count { it.value }.toFloat() / analysisStats.totalSamplesAnalyzed * 100)
            .let {
                when {
                    it.isNaN() -> 0f
                    it.isInfinite() && it > 0 -> 100f
                    it.isInfinite() && it < 0 -> 0f
                    else -> it
                }
            }
    /*cell(
        Text(
            "Alive samples: ${analysisStats.samplesStatus.count { it.value }} ($alivePercent% of analyzed samples)",
            whitespace = Whitespace.NORMAL
        )
    )*/
    val analyzedPercent = (analysisStats.totalSamplesAnalyzed.toFloat() / analysisStats.totalSamples * 100)
        .let {
            when {
                it.isNaN() -> 0f
                it.isInfinite() && it > 0 -> 100f
                it.isInfinite() && it < 0 -> 0f
                else -> it
            }
        }
    /*cell(
        Text(
            "Analyzed samples: ${analysisStats.totalSamplesAnalyzed} ($analyzedPercent% of collected samples)",
            whitespace = Whitespace.NORMAL
        )
    )*/
    cell(
        grid {
            row {
                cell(Text("Alive samples"))
                cell(Text("${analysisStats.samplesStatus.count { it.value }} (${"%.2f".format(alivePercent)}% of analyzed samples)"))
            }
            row {
                cell(Text("Analyzed samples"))
                cell(Text("${analysisStats.totalSamplesAnalyzed} (${"%.2f".format(analyzedPercent)}% of samples left)"))
            }
        }
    )
}

private fun analysisPhaseTitle() =
    """
____ _  _ ____ _   _   _ ____ _ ____ 
|__| |\ | |__| |    \_/  [__  | [__  
|  | | \| |  | |___  |   ___] | ___]
"""