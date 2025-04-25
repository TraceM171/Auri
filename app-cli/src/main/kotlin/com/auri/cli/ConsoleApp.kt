package com.auri.cli

import arrow.continuations.SuspendApp
import co.touchlab.kermit.Logger
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.MordantHelpFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) = SuspendApp(
    timeout = 5.seconds,
    uncaught = {
        when (it) {
            is TimeoutCancellationException -> Logger.w { "Timed out performing a graceful shutdown" }
            is CancellationException -> Unit
            else -> Logger.e(it) { "Uncaught exception in SuspendApp" }
        }
    }
) { Auri().main(args) }

private class Auri : SuspendingNoOpCliktCommand() {
    override val printHelpOnEmptyArgs = true
    override fun help(context: Context): String = """
        AURI (Automated Unified Ransomware Intelligence) is a ransomware analysis pipeline that is designed to
        provide a repeatable and automated way to analyze ransomware behavior and detection capabilities of security
        products.
    """.trimIndent()

    init {
        subcommands(Collection(), Liveness())
        context {
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }
}
