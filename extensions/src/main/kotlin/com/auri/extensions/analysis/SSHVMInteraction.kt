package com.auri.extensions.analysis

import arrow.core.Either
import arrow.core.raise.either
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.resourceScope
import arrow.resilience.Schedule
import arrow.resilience.retryEither
import co.touchlab.kermit.Logger
import com.auri.core.analysis.VMInteraction
import com.auri.core.common.util.catching
import com.auri.core.common.util.ctx
import com.auri.core.common.util.installF
import com.auri.core.common.util.linesFlow
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
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

    override suspend fun awaitReady(): Either<Throwable, Unit> = withContext(dispatcher) {
        Schedule
            .spaced<Throwable>(2.seconds)
            .and(Schedule.recurs(10))
            .retryEither {
                resourceScope {
                    init().map { }
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
                        Logger.v { "Running command: $command" }
                        installF(
                            {},
                            { commandInputChannel.close() }
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
                                catching { session.openChannel("exec") as ChannelExec }
                                    .ctx("Creating SSH channel")
                                    .bind()
                            },
                            {
                                catching(it::disconnect)
                                    .ctx("Disconnecting SSH channel")
                                    .bind()
                            }
                        )
                        channel.setCommand(command)
                        catching { channel.connect(definition.connectionTimeout.inWholeMilliseconds.toInt()) }
                            .ctx("Connecting to SSH channel")
                            .bind()
                        Logger.d { "Waiting for command to finish" }
                        val channelInputStream = installF(
                            { channel.inputStream.bufferedReader() },
                            { it.close() }
                        )
                        val channelOutputStream = installF(
                            { channel.outputStream.bufferedWriter() },
                            { it.close() }
                        )
                        val channelErrorStream = installF(
                            { channel.errStream.bufferedReader() },
                            { it.close() }
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
                        Logger.d { "Command finished" }
                        channel.exitStatus
                    }.also {
                        Logger.d { "Command resources released" }
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
    ): Either<Throwable, Unit> = either {
        withContext(dispatcher) {
            resourceScope {
                Logger.d { "Sending file to $remotePath" }
                val session = init().bind()

                val channel = installF(
                    {
                        catching { session.openChannel("sftp") as ChannelSftp }
                            .ctx("Creating SFTP channel")
                            .bind()
                    },
                    {
                        catching(it::disconnect)
                            .ctx("Disconnecting SFTP channel")
                            .bind()
                    }
                )
                catching(channel::connect)
                    .ctx("Connecting to SFTP channel")
                    .bind()
                val took = measureTime {
                    catching { channel.put(source, "/" + remotePath.pathString.removePrefix("/")) }
                        .ctx("Sending to channel")
                        .bind()
                }
                Logger.d { "File sent in $took" }
            }
        }
    }

    private suspend fun ResourceScope.init() = either {
        val jsch = JSch()

        val session = installF(
            {
                catching { jsch.getSession(definition.username, definition.host.hostAddress, definition.port) }
                    .ctx("Creating SSH session")
                    .bind()
            },
            {
                catching(it::disconnect)
                    .ctx("Disconnecting SSH session")
                    .bind()
            }
        ).apply {
            setPassword(definition.password)
            setConfig("StrictHostKeyChecking", "no")
        }
        catching { session.connect(definition.connectionTimeout.inWholeMilliseconds.toInt()) }
            .ctx("Connecting to SSH session")
            .bind()

        session!!
    }

    override fun close() {
        dispatcher.close()
    }
}
