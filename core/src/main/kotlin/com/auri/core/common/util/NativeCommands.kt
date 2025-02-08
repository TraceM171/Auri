package com.auri.core.common.util

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.io.File

suspend fun runNativeCommand(
    workingDir: File? = null,
    vararg command: String
): Either<String, String> = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(*command).apply {
        directory(workingDir)
    }.start()
    val outputAsync = async {
        process.inputStream.bufferedReader().useLines {
            it.onEach {
                Logger.d { it }
            }.joinToString(separator = "\n")
        }
    }
    val errorAsync = async {
        process.errorStream.bufferedReader().useLines {
            it.onEach {
                Logger.d { it }
            }.joinToString(separator = "\n")
        }
    }
    val exitCode = process.onExit().await().exitValue()
    when (exitCode) {
        0 -> outputAsync.await().right()
        else -> errorAsync.await().left()
    }
}