package com.auri.cli.common

import co.touchlab.kermit.Severity
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.options.counted
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlin.io.path.Path
import kotlin.io.path.pathString


fun ParameterHolder.baseDirectory() = option(
    "-b", "--base-directory",
    help = "Base directory to store files. Will be created if it does not exist."
).path(mustExist = false, canBeFile = false, canBeDir = true)
    .defaultLazy(defaultForHelp = Path("./.auri").pathString) {
        Path("./.auri")
    }

fun ParameterHolder.runbook() = option(
    "-r", "--runbook",
    help = "AURI Runbook file."
).path(mustExist = true, canBeFile = true, canBeDir = false, mustBeReadable = true)
    .defaultLazy(defaultForHelp = Path("./runbook.yml").pathString) {
        Path("./runbook.yml")
    }

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

fun ParameterHolder.streamed() = option(
    "-s", "--streamed",
    help = "Never finishes the phase, it will keep waiting for new samples to be collected."
).flag(
    "--no-streamed",
    default = false,
    defaultForHelp = "false"
)

fun verbosityToSeverity(verbosity: Int): Severity = when (verbosity) {
    1 -> Severity.Info
    2 -> Severity.Debug
    3 -> Severity.Verbose
    else -> Severity.Warn
}