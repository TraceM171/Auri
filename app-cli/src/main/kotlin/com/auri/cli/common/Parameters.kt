package com.auri.cli.common

import co.touchlab.kermit.Severity
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import java.nio.file.Paths


fun ParameterHolder.baseDirectory() = option(
    "-b", "--base-directory",
    help = "Base directory to store files. Will be created if it does not exist."
).file(mustExist = false, canBeFile = false, canBeDir = true)
    .defaultLazy(defaultForHelp = File("./.auri").path) {
        Paths.get(".").toAbsolutePath().normalize().toFile().resolve(".auri")
    }

fun ParameterHolder.runbook() = option(
    "-r", "--runbook",
    help = "AURI Runbook file."
).file(mustExist = true, canBeFile = true, canBeDir = false, mustBeReadable = true)
    .defaultLazy(defaultForHelp = File("./runbook.yml").path) {
        File("./runbook.yml")
    }.check("Runbook file not found") { it.exists() }

fun ParameterHolder.pruneCache() = option(
    "-p", "--prune-cache",
    help = "Prune all cached files."
).flag(
    "--no-prune-cache",
    default = false,
    defaultForHelp = "false"
)

fun ParameterHolder.verbosity() = option(
    "-v",
    help = "Set verbosity to use when logging."
).counted(limit = 3, clamp = true)

fun verbosityToSeverity(verbosity: Int): Severity = when (verbosity) {
    1 -> Severity.Info
    2 -> Severity.Debug
    3 -> Severity.Verbose
    else -> Severity.Warn
}