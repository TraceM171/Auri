package com.auri.cli

import com.auri.app.launchSampleCollection
import com.auri.collection.CollectionProcessStatus
import com.auri.core.collection.CollectorStatus
import com.auri.core.common.util.chainIf
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.widgets.Text
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.nio.file.Paths

suspend fun main(args: Array<String>) = Auri()
    .main(args)

private class Auri : SuspendingNoOpCliktCommand() {
    override val printHelpOnEmptyArgs = true
    override fun help(context: Context): String = """
        AURI (Automated Unified Ransomware Intelligence) is a ransomware analysis pipeline that is designed to
        provide a repeatable and automated way to analyze ransomware behavior and detection capabilities of security
        products.
    """.trimIndent()

    init {
        subcommands(Collection())
        context {
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }
}

private class Manage : SuspendingNoOpCliktCommand(name = "manage") {
    override fun help(context: Context): String = """
        This enables you to manage the AURI pipeline.
    """.trimIndent()

    override suspend fun run(): Unit = Unit
}

private class Collection : SuspendingCliktCommand(name = "collection") {
    override fun help(context: Context): String = """
        The Collection Phase is the first phase of the AURI pipeline. It is responsible for collecting malware samples
        from various sources and storing them in a database.
    """.trimIndent()

    private val baseDirectory: File by baseDirectory()
    private val runbook: File by runbook()
    private val pruneCache: Boolean by pruneCache()

    override suspend fun run(): Unit = coroutineScope {
        launchSampleCollection(
            baseDirectory = baseDirectory,
            runbook = runbook,
            pruneCache = pruneCache
        ).render()
    }

    private suspend fun Flow<CollectionProcessStatus>.render() {
        val tui = terminal.animation<CollectionProcessStatus> { processStatus ->
            verticalLayout {
                cell(Text("Phase: Collection"))
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

                                        CollectorStatus.DoneUntilNextPeriod -> cell("Done until next period") {
                                            style(color = TextColors.green)
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

                                        is CollectorStatus.Retrying -> cell("Retrying ${status.what}: ${status.why}") {
                                            style(color = TextColors.red)
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
        collect {
            tui.update(it)
        }
    }
}


private fun ParameterHolder.baseDirectory() = option(
    "-b", "--base-directory",
    help = "Base directory to store files. Will be created if it does not exist."
).file(mustExist = false, canBeFile = false, canBeDir = true)
    .defaultLazy(defaultForHelp = File("./.auri").path) {
        Paths.get(".").toAbsolutePath().normalize().toFile().resolve(".auri")
    }

private fun ParameterHolder.runbook() = option(
    "-r", "--runbook",
    help = "AURI Runbook file."
).file(mustExist = true, canBeFile = true, canBeDir = false, mustBeReadable = true)
    .defaultLazy(defaultForHelp = File("./runbook.yml").path) {
        File("./runbook.yml")
    }.check("Runbook file not found") { it.exists() }

private fun ParameterHolder.pruneCache() = option(
    "-p", "--prune-cache",
    help = "Prune all cached files."
).flag(
    "--no-prune-cache",
    default = false,
    defaultForHelp = "false"
)


private fun String.elipsis(maxLength: Int, elipsisText: String = "…"): String {
    val tipPointLength = (maxLength - elipsisText.length).coerceAtLeast(0)
    return chainIf(length > tipPointLength) {
        buildString {
            append(this@elipsis.take(tipPointLength))
            append(elipsisText)
        }
    }
}