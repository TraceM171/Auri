package com.auri.cli

import com.auri.app.collection.CollectionProcessStatus
import com.auri.app.launchSampleCollection
import com.auri.cli.common.*
import com.auri.core.collection.CollectorStatus
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.rendering.BorderType
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.table.Borders
import com.github.ajalt.mordant.table.VerticalLayoutBuilder
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.widgets.definitionList
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import java.io.File
import kotlin.time.Duration.Companion.seconds

class Collection : SuspendingCliktCommand(name = "collection") {
    private val phaseDescription = """
        The Collection Phase is the first phase of the AURI pipeline. It is responsible for collecting malware samples from various sources and storing them in the database.
    """.trimIndent()

    override fun help(context: Context): String = phaseDescription

    private val baseDirectory: File by baseDirectory()
    private val runbook: File by runbook()
    private val pruneCache: Boolean by pruneCache()
    private val verbosity: Int by verbosity()

    override suspend fun run(): Unit = coroutineScope {
        val collectionProcessStatus = launchSampleCollection(
            baseDirectory = baseDirectory,
            runbook = runbook,
            pruneCache = pruneCache,
            minLogSeverity = verbosityToSeverity(verbosity)
        )
        terminal.baseAuriTui(
            phaseTitle = collectionPhaseTitle(),
            phaseDescription = phaseDescription,
            phaseData = collectionProcessStatus,
            phaseTui = VerticalLayoutBuilder::tui
        )
    }
}

private fun VerticalLayoutBuilder.tui(
    processStatus: CollectionProcessStatus
) {
    when (processStatus) {
        CollectionProcessStatus.NotStarted -> {
            cell("Not started") {
                style(bold = true)
            }
            cell("The collection will begin shortly")
        }

        CollectionProcessStatus.Initializing -> {
            cell("Initializing") {
                style(bold = true)
            }
            cell("The collectors are being initialized")
        }

        is CollectionProcessStatus.Collecting -> {
            cell("Collecting") {
                style(bold = true)
            }
            cell(
                """
                All the collectors have been started and its status is shown in the table bellow.
                This phase will stop when all all the collectors finish.
                Note that, depending on the runbook, some collectors may be scheduled periodically, thus they may never finish, in this case the phase can be ended by pressing [^C] when the user desires, samples are saved on receive, so none will be lost by ending forcefully.
            """.trimIndent()
            )
        }

        is CollectionProcessStatus.Finished -> {
            cell("Finished") {
                style(bold = true)
            }
            cell(
                """
                The collection has finished because all the collectors ended and none are scheduled.
                All samples have been saved.
            """.trimIndent()
            )
        }

        is CollectionProcessStatus.MissingDependencies -> {
            cell("Some needed dependencies are missing") {
                style(bold = true, color = TextColors.brightRed)
            }
            cell(
                definitionList {
                    processStatus.missingDependencies.forEach { collector ->
                        entry {
                            term("-> Collector ${collector.key.name}:")
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

        is CollectionProcessStatus.Failed -> {
            cell("Failed") {
                style(bold = true, color = TextColors.brightRed)
            }
            cell("An error occurred in ${processStatus.what}: ${processStatus.why}") {
                style(color = TextColors.brightRed)
            }
        }
    }
    run {
        val collectionStats = when (processStatus) {
            is CollectionProcessStatus.Collecting -> processStatus.collectionStats
            is CollectionProcessStatus.Finished -> processStatus.collectionStats
            is CollectionProcessStatus.Failed,
            CollectionProcessStatus.Initializing,
            is CollectionProcessStatus.MissingDependencies,
            CollectionProcessStatus.NotStarted -> return@run
        }
        cell("")
        collectionStatsTui(collectionStats)
        cell("")
    }
}

private fun VerticalLayoutBuilder.collectionStatsTui(collectionStats: CollectionProcessStatus.CollectionStats) {
    cell(table {
        borderType = BorderType.SQUARE_DOUBLE_SECTION_SEPARATOR
        tableBorders = Borders.ALL
        header {
            style = TextStyle(bold = true)
            row("Source", "Samples", "Status")
        }
        body {
            collectionStats.collectorsStatus.forEach { (collector, status) ->
                row {
                    cell(collector.name)
                    cell(collectionStats.samplesCollectedByCollector[collector]) {
                        align = TextAlign.CENTER
                    }
                    when (status) {
                        CollectorStatus.Done -> cell("Done") {
                            style(color = TextColors.brightGreen)
                        }

                        is CollectorStatus.DoneUntilNextPeriod -> {
                            val nextPeriodIn = status.nextPeriodStart - Clock.System.now()
                            cell("Done until next period (${nextPeriodIn.inWholeSeconds.seconds})") {
                                style(color = TextColors.green)
                            }
                        }

                        is CollectorStatus.Downloading -> cell("Downloading ${status.what.elipsis(25)}") {
                            style(color = TextColors.brightBlue)
                        }

                        is CollectorStatus.NewSample -> cell("New sample ${status.sample.name.elipsis(20)}") {
                            style(color = TextColors.brightCyan)
                        }

                        is CollectorStatus.Processing -> cell("Processing ${status.what.elipsis(25)}") {
                            style(color = TextColors.blue)
                        }

                        is CollectorStatus.Failed -> cell("Failed ${status.what}: ${status.why}") {
                            style(color = TextColors.brightRed)
                        }

                        is CollectorStatus.Retrying -> {
                            val nextTryIn = status.nextTryStart - Clock.System.now()
                            cell("Retrying ${status.what} (${nextTryIn.inWholeSeconds.seconds}): ${status.why}") {
                                style(color = TextColors.red)
                            }
                        }

                        null -> cell("N/A") {
                            style(color = TextColors.gray)
                        }
                    }
                }
            }
        }
        footer {
            style(italic = true, bold = true)
            row {
                cell("Total") {
                    align = TextAlign.RIGHT
                }
                cell(collectionStats.totalSamplesCollected) {
                    align = TextAlign.CENTER
                }
                cell("")
            }
        }
    })
}

private fun collectionPhaseTitle() =
    """
____ ____ _    _    ____ ____ ___ _ ____ _  _ 
|    |  | |    |    |___ |     |  | |  | |\ | 
|___ |__| |___ |___ |___ |___  |  | |__| | \|
"""