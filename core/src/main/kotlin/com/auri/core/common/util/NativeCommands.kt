package com.auri.core.common.util

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.nio.file.Path

suspend fun runNativeCommand(
    workingDir: Path? = null,
    vararg command: String
): Either<String, String> = withContext(Dispatchers.IO) {
    val process = runCatching {
        ProcessBuilder(*command).apply {
            directory(workingDir?.toFile())
        }.start()
    }.getOrElse {
        Logger.d(it) { "Error running command: ${command.joinToString(" ")}" }
        return@withContext (it.message ?: "Unknown error").left()
    }
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