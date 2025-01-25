package com.auri.core

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main

class Auri : CliktCommand() {
    override fun run() = Unit
}

fun main(args: Array<String>) = Auri()
    .main(args)