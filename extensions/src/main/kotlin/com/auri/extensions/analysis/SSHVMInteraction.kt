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
import com.auri.core.common.util.linesFlow
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Path
import java.util.concurrent.Executors
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

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    data class Definition(
        val customName: String = "SSH VM Interaction",
        val host: InetAddress,
        val port: Int = 22,
        val username: String,
        val password: String,
        val connectionTimeout: Duration = 5.seconds,
    )

    override suspend fun awaitReady(): Either<Unit, Unit> = withContext(dispatcher) {
        Schedule
            .spaced<Unit>(2.seconds)
            .and(Schedule.recurs(10))
            .retryEither {
                resourceScope {
                    init(testConn = true).map { }
                }
            }
    }

    override fun prepareCommand(command: String): VMInteraction.Command {
        val commandInputChannel = Channel<String>()
        val commandOutputChannel = Channel<String>()
        val commandErrorChannel = Channel<String>()

        val runBlock = suspend {
            either {
                withContext(dispatcher) {
                    resourceScope {
                        Logger.d { "Running command: $command" }
                        installF(
                            {},
                            { commandInputChannel.cancel() }
                        )
                        installF(
                            {},
                            { commandOutputChannel.close() }
                        )
                        installF(
                            {},
                            { commandErrorChannel.close() }
                        )
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
                        Logger.d { "Waiting for command to finish" }
                        val channelInputStream = installF(
                            { channel.inputStream.bufferedReader() },
                            BufferedReader::close
                        )
                        val channelOutputStream = installF(
                            { channel.outputStream.bufferedWriter() },
                            BufferedWriter::close
                        )
                        val channelErrorStream = installF(
                            { channel.errStream.bufferedReader() },
                            BufferedReader::close
                        )
                        val commandChannelsJob = Job()
                        launch(commandChannelsJob) {
                            commandInputChannel.consumeAsFlow().collect { input ->
                                channelOutputStream.write(input)
                                channelOutputStream.flush()
                            }
                        }
                        launch(commandChannelsJob) {
                            channelInputStream.linesFlow().collect { line ->
                                commandOutputChannel.send(line)
                            }
                        }
                        launch(commandChannelsJob) {
                            channelErrorStream.linesFlow().collect { line ->
                                commandErrorChannel.send(line)
                            }
                        }
                        val took = measureTime { while (!channel.isClosed) delay(100.milliseconds) }
                        Logger.d { "Command took $took" }
                        commandChannelsJob.cancelAndJoin()
                        channel.exitStatus
                    }
                }
            }
        }
        val commandObject = VMInteraction.Command(
            commandInput = commandInputChannel,
            commandOutput = commandOutputChannel,
            commandError = commandErrorChannel,
            run = runBlock
        )
        return commandObject
    }

    override suspend fun sendFile(
        source: InputStream,
        remotePath: Path
    ): Either<Unit, Unit> = either {
        withContext(dispatcher) {
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
            {
                session.connect(definition.connectionTimeout.inWholeMilliseconds.toInt())
            },
            {
                if (!testConn) Logger.w { "Failed to connect to SSH session" }
                raise(Unit)
            }
        )
        session!!
    }
}
