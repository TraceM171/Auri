package com.auri.extensions.analysis

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.resourceScope
import arrow.resilience.Schedule
import arrow.resilience.retryEither
import co.touchlab.kermit.Logger
import com.auri.core.analysis.VMInteraction
import com.auri.core.common.util.catchLog
import com.auri.core.common.util.installF
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.*
import java.net.InetAddress
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class SSHVMInteraction(
    private val definition: Definition
) : VMInteraction {
    override val name: String = definition.customName
    override val description: String = "Interact with a VM over SSH"
    override val version: String = "0.0.1"

    data class Definition(
        val customName: String = "SSH VM Interaction",
        val host: InetAddress,
        val port: Int = 22,
        val username: String,
        val password: String,
        val connectionTimeout: Duration = 5.seconds,
    )

    override suspend fun awaitReady(): Either<Unit, Unit> = Schedule
        .spaced<Unit>(2.seconds)
        .and(Schedule.recurs(10))
        .retryEither {
            resourceScope {
                init(testConn = true).map { }
            }
        }

    override fun prepareCommand(command: String): VMInteraction.Command {
        val commandInput = CompletableDeferred<BufferedWriter>()
        val commandOutput = CompletableDeferred<BufferedReader>()
        val commandError = CompletableDeferred<BufferedReader>()

        val runBlock = suspend {
            either {
                withContext(Dispatchers.IO) {
                    resourceScope {
                        Logger.d { "Running command: $command" }
                        val session = init().bind()

                        val channel = installF(
                            {
                                catchLog("Failed to create SSH channel")
                                { session.openChannel("exec") as ChannelExec }
                            },
                            release = ChannelExec::disconnect
                        )
                        channel.setCommand(command)
                        catchLog("Failed to connect to SSH channel") { channel.connect(definition.connectionTimeout.inWholeMilliseconds.toInt()) }
                        channel.apply {
                            installF(
                                { outputStream.bufferedWriter() },
                                release = BufferedWriter::close
                            ).let(commandInput::complete)
                            installF(
                                { inputStream.bufferedReader() },
                                release = BufferedReader::close
                            ).let(commandOutput::complete)
                            installF(
                                { errStream.bufferedReader() },
                                release = BufferedReader::close
                            ).let(commandError::complete)
                        }
                        Logger.d { "Waiting for command to finish" }
                        val took = measureTime { while (!channel.isClosed) delay(100.milliseconds) }
                        Logger.d { "Command took $took" }
                        channel.exitStatus
                    }
                }
            }.onLeft {
                if (!commandInput.isCompleted)
                    commandInput.complete(Writer.nullWriter().buffered())
                if (!commandOutput.isCompleted)
                    commandOutput.complete(Reader.nullReader().buffered())
                if (!commandError.isCompleted)
                    commandError.complete(Reader.nullReader().buffered())
            }
        }
        val commandObject = VMInteraction.Command(
            commandInput = commandInput,
            commandOutput = commandOutput,
            commandError = commandError,
            run = runBlock
        )
        return commandObject
    }

    override suspend fun sendFile(
        source: InputStream,
        remotePath: Path
    ): Either<Unit, Unit> = either {
        withContext(Dispatchers.IO) {
            resourceScope {
                Logger.d { "Sending file to $remotePath" }
                val session = init().bind()

                val channel = installF(
                    {
                        catchLog("Failed to create SFTP channel")
                        { session.openChannel("sftp") as ChannelSftp }
                    },
                    ChannelSftp::disconnect
                )
                catchLog("Failed to connect to SFTP channel", channel::connect)
                val took = measureTime {
                    catchLog("Failed to send file")
                    { channel.put(source, "/" + remotePath.pathString.removePrefix("/")) }
                }
                Logger.d { "File sent in $took" }
            }
        }
    }

    private suspend fun ResourceScope.init(testConn: Boolean = false) = either {
        val jsch = JSch()

        val session = installF(
            {
                catchLog("Failed to create SSH session")
                { jsch.getSession(definition.username, definition.host.hostAddress, definition.port) }
            },
            Session::disconnect
        ).apply {
            setPassword(definition.password)
            setConfig("StrictHostKeyChecking", "no")
        }
        catch(
            { session.connect(definition.connectionTimeout.inWholeMilliseconds.toInt()) },
            {
                if (!testConn) Logger.w { "Failed to connect to SSH session" }
                raise(Unit)
            }
        )
        session!!
    }
}
