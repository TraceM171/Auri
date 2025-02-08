package com.auri.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main

class Auri : CliktCommand() {
    override val printHelpOnEmptyArgs = true
    override fun run() = Unit
}

fun main(args: Array<String>) = Auri()
    .main(args)