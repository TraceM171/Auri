package com.auri.core.analysis

import arrow.core.Either
import arrow.core.raise.either
import com.auri.core.common.ExtensionPoint
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.nio.file.Path

/**
 * Interface for interacting with a virtual machine.
 *
 * This interface provides methods for sending commands to a virtual machine and receiving the output.
 */
@ExtensionPoint
interface VMInteraction {
    /**
     * The name of the VM interaction. Must be unique for each VM interaction.
     */
    val name: String

    /**
     * A description of the VM interaction.
     */
    val description: String

    /**
     * The version of the VM interaction.
     */
    val version: String


    /**
     * Awaits for the virtual machine to be ready to receive commands.
     *
     * @return An [Either] that represents the result of the operation.
     *        [Either.Left] if the operation failed, [Either.Right] if the operation succeeded.
     */
    suspend fun awaitReady(): Either<Unit, Unit>

    /**
     * Prepares a command to be sent to a virtual machine.
     *
     * @param command The command to execute.
     * @return A [Command] object that represents the command, ready to be run.
     */
    fun prepareCommand(command: String): Command

    /**
     * Sends a file to a virtual machine.
     *
     * If a file with the path [remotePath] already exists on the virtual machine, it will be overwritten.
     *
     * @param source The input stream for the file.
     * @param remotePath The path on the virtual machine where the file should be saved.
     * @return An [Either] that represents the result of the operation.
     *        [Either.Left] if the operation failed, [Either.Right] if the operation succeeded.
     */
    suspend fun sendFile(source: InputStream, remotePath: Path): Either<Unit, Unit>


    /**
     * A command that can be sent to a virtual machine.
     *
     * This command is not ran until [run] is called.
     */
    data class Command(
        /**
         * The input stream (stdin) for the command.
         */
        val commandInput: Deferred<BufferedWriter>,
        /**
         * The output stream (stdout) for the command.
         */
        val commandOutput: Deferred<BufferedReader>,
        /**
         * The error stream (stderr) for the command.
         */
        val commandError: Deferred<BufferedReader>,
        private val run: suspend () -> Either<Unit, Int>
    ) {
        /**
         * Runs the command.
         *
         * This method will suspend until the command is finished,
         * if interaction with the command streams is needed, this method should be called from a separate coroutine.
         *
         * For a convenience method that manages the streams automatically, see [runAndGetOutput].
         *
         * @return An [Either] that represents the result of the command.
         *        [Either.Left] if the command failed, [Either.Right] if the command succeeded with the exit code.
         *
         * @see runAndGetOutput
         */
        suspend fun run() = run.invoke()

        /**
         * Runs the command sending the given input, waits for it to finish and returns the output.
         *
         * This method will take care of managing the command streams, caller should never interact with them.
         * If interaction with the streams is needed, use [run] instead and manage the streams manually.
         *
         * @param input The input to send to the command.
         * @return An [Either] that represents the result of the command.
         *       [Either.Left] if the command could not be run, [Either.Right] if the command was run.
         *
         * @see CodeWithOutput
         * @see run
         */
        suspend fun runAndGetOutput(
            input: String? = null
        ) = either {
            coroutineScope {
                val output = async(Dispatchers.IO) {
                    buildString {
                        commandError.await().useLines { it.forEach(::appendLine) }
                    }
                }
                val error = async(Dispatchers.IO) {
                    buildString {
                        commandOutput.await().useLines { it.forEach(::appendLine) }
                    }
                }
                launch(Dispatchers.IO) {
                    if (input != null) {
                        commandInput.await().write(input)
                        commandInput.await().flush()
                    }
                    commandInput.await().close()
                }
                val code = run().bind()
                CodeWithOutput(
                    code = code,
                    output = output.await(),
                    error = error.await()
                )
            }
        }


        data class CodeWithOutput(val code: Int, val output: String, val error: String)
    }
}