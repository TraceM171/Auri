package com.auri.core.analysis

import arrow.core.Either
import arrow.core.raise.either
import com.auri.core.common.ExtensionPoint
import com.auri.core.common.HasDependencies
import com.auri.core.common.MissingDependency
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.nio.file.Path

/**
 * Interface for interacting with a virtual machine.
 *
 * This interface provides methods for sending commands to a virtual machine and receiving the output.
 */
@ExtensionPoint
interface VMInteraction : HasDependencies, AutoCloseable {
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
    suspend fun awaitReady(): Either<Throwable, Unit>

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
    suspend fun sendFile(source: InputStream, remotePath: Path): Either<Throwable, Unit>


    override suspend fun checkDependencies(): List<MissingDependency> = emptyList()

    override fun close() = Unit


    /**
     * A command that can be sent to a virtual machine.
     *
     * This command is not ran until [run] is called.
     */
    data class Command(
        /**
         * The input stream (stdin) for the command.
         */
        val commandInput: SendChannel<String>,
        /**
         * The output stream (stdout) for the command.
         */
        val commandOutput: ReceiveChannel<String>,
        /**
         * The error stream (stderr) for the command.
         */
        val commandError: ReceiveChannel<String>,
        private val run: suspend () -> Either<Throwable, Int>
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
                val output = async {
                    buildString {
                        commandError.consumeAsFlow().collect(::appendLine)
                    }
                }
                val error = async {
                    buildString {
                        commandOutput.consumeAsFlow().collect(::appendLine)
                    }
                }
                if (input != null) launch {
                    commandInput.send(input)
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