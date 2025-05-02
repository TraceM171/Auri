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
        Prune samples directory, removing all files not declared as alive samples by the AURI pipeline.
        This will not remove the database entries, only the executable files.
        This will cause issues when trying to use samples not declared as alive in further operations.
        This command is not reversible, so be careful when using it.
        Note: ANY file not declared as alive will be deleted from the samples directory, even if it is not a sample.
    """.trimIndent()

    private val baseDirectory: Path by baseDirectory()
    private val runbook: Path by runbook()
    private val verbosity: Int by verbosity()

    override suspend fun run() {
        val result = pruneSamples(
            baseDirectory = baseDirectory,
            runbook = runbook,
            minLogSeverity = verbosityToSeverity(verbosity),
        )

        echo("Pruned ${result.prunedSamples} samples, freed ${result.bytesFreed} bytes")
    }
}