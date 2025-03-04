package com.auri.cli

import com.auri.app.collection.CollectionProcessStatus
import com.auri.app.launchSampleCollection
import com.auri.core.collection.CollectorStatus
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Text
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import java.io.File
import kotlin.time.Duration.Companion.seconds

class Collection : SuspendingCliktCommand(name = "collection") {
    override fun help(context: Context): String = """
        The Collection Phase is the first phase of the AURI pipeline. It is responsible for collecting malware samples
        from various sources and storing them in a database.
    """.trimIndent()

    private val baseDirectory: File by baseDirectory()
    private val runbook: File by runbook()
    private val pruneCache: Boolean by pruneCache()

    override suspend fun run(): Unit = coroutineScope {
        val collectionProcessStatus = launchSampleCollection(
            baseDirectory = baseDirectory,
            runbook = runbook,
            pruneCache = pruneCache
        )
        terminal.run {
            println(appTitle())
            println(collectionPhaseTitle())
            tui(collectionProcessStatus)
        }
    }
}

private suspend fun Terminal.tui(
    collectionProcessStatus: Flow<CollectionProcessStatus>
) {
    val tui = animation<CollectionProcessStatus> { processStatus ->
        verticalLayout {
            cell(Text("Status: ${processStatus::class.simpleName}"))
            if (processStatus is CollectionProcessStatus.Collecting) {
                cell(Text("Samples collected: ${processStatus.totalSamplesCollected}"))
                cell(table {
                    header {
                        row("Source", "Samples", "Status")
                    }
                    body {
                        processStatus.collectorsStatus.forEach { (collector, status) ->
                            row {
                                cell(collector.name)
                                cell(processStatus.samplesCollectedByCollector[collector])
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
                })
                cell(Text("Samples with info: ${processStatus.totalSamplesWithInfo}"))
                cell(table {
                    header {
                        row("Provider", "Info found")
                    }
                    body {
                        processStatus.samplesWithInfoByProvider.forEach { (provider, samplesWithInfo) ->
                            row {
                                cell(provider.name)
                                cell(samplesWithInfo)
                            }
                        }
                    }
                })
            }
        }
    }
    val minUpdateRateFlow = flow {
        while (true) {
            delay(1.seconds)
            emit(Unit)
        }
    }
    collectionProcessStatus.combine(minUpdateRateFlow) { it, _ ->
        it
    }.collect {
        tui.update(it)
    }
}