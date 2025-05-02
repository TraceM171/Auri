package com.auri.cli

import com.auri.app.pruneSamples
import com.auri.cli.common.baseDirectory
import com.auri.cli.common.runbook
import com.auri.cli.common.verbosity
import com.auri.cli.common.verbosityToSeverity
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Path

class Manage : SuspendingNoOpCliktCommand(name = "manage") {
    override fun help(context: Context): String = """
        Utilities to manage the AURI pipeline.
    """.trimIndent()

    init {
        subcommands(PruneSamples())
    }
}

private class PruneSamples : SuspendingCliktCommand(name = "prune-samples") {
    override fun help(context: Context): String = """
        Prune samples that have been marked as dead by the AURI pipeline.
        This will not delete the database entries, only the executable files, to free up space.
        Trying to use deleted sample files will result in issues, so re-analyzing this samples will not be possible.
        This operation may be performed simultaneously with any phase of the pipeline.
        $dataDeletionWarning
    """.trimIndent()

    private val baseDirectory: Path by baseDirectory()
    private val runbook: Path by runbook()
    private val verbosity: Int by verbosity()
    private val aggressive: Boolean by option(
        "--aggressive",
        help = """
            This will also delete samples that have not been analyzed yet, are not present in the database, or files unrelated to the AURI pipeline present in the samples directory.
            This operation may interfere with the AURI pipeline, offline use is recommended.
        """.trimIndent()
    ).flag(
        "--no-aggressive",
        default = false,
        defaultForHelp = "false"
    )

    override suspend fun run() {
        val result = pruneSamples(
            baseDirectory = baseDirectory,
            runbook = runbook,
            minLogSeverity = verbosityToSeverity(verbosity),
            aggressive = aggressive,
        )

        echo("Pruned ${result.prunedSamples} samples, freed ${result.bytesFreed} bytes")
    }
}

private const val dataDeletionWarning =
    "NOTE: This operation will delete data irreversibly, please ensure you know what you are doing."