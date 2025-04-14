package com.auri.extensions.analysis

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.resourceScope
import arrow.resilience.Schedule
import arrow.resilience.retryRaise
import co.touchlab.kermit.Logger
import com.auri.core.analysis.VMManager
import com.auri.core.common.util.catching
import com.auri.core.common.util.ctx
import com.auri.core.common.util.installF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.virtualbox_7_1.LockType
import org.virtualbox_7_1.MachineState
import org.virtualbox_7_1.SessionState
import org.virtualbox_7_1.VirtualBoxManager
import java.net.URI
import java.net.URL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class VirtualBoxVMManager(
    private val definition: Definition
) : VMManager {
    override val name: String = definition.customName
    override val description: String = "Manage VirtualBox VMs"
    override val version: String = "0.0.1"

    data class Definition(
        val customName: String = "VirtualBox",
        val vmName: String,
        val vmSnapshot: String,
        val launchMode: LaunchMode = LaunchMode.GUI,
        val launchTimeout: Duration = 1.minutes,
        val endpoint: URL = URI.create("http://localhost:18083").toURL(),
        val username: String? = null,
        val password: String? = null,
    )

    override suspend fun launchVM() = either {
        resourceScope {
            withContext(Dispatchers.IO) {
                val vbManager = init().bind()
                val vbSession = vbManager.sessionObject
                val vbox = vbManager.vBox
                val vbMachine = catching { vbox.findMachine(definition.vmName) }
                    .ctx("Finding VM")
                    .bind()
                Logger.d { "Found VM: ${vbMachine.name}" }
                val snapshot = catching { vbMachine.findSnapshot(definition.vmSnapshot) }
                    .ctx("Finding VM snapshot")
                    .bind()
                Logger.d { "Found snapshot: ${snapshot.name}" }
                ensure(snapshot.online) {
                    Throwable("Snapshot is not online")
                }
                installF(
                    {
                        catching { vbMachine.lockMachine(vbSession, LockType.Shared) }
                            .ctx("Locking VM")
                            .bind()
                    },
                    {
                        catching(vbSession::unlockMachine)
                            .ctx("Unlocking VM in release")
                            .bind()
                    }
                )
                val currentMachineStatus = catching { vbMachine.state }
                    .ctx("Getting VM state")
                    .bind()
                Logger.d { "Current VM state: $currentMachineStatus" }
                when (currentMachineStatus) {
                    MachineState.PoweredOff -> Unit
                    MachineState.Saved,
                    MachineState.AbortedSaved -> catching {
                        vbSession.machine.restoreSnapshot(snapshot)
                    }.ctx("Restoring VM state")
                        .bind()

                    else -> {
                        Logger.w { "VM is in an incorrect state: $currentMachineStatus, trying to stop it" }
                        stopVM().bind()
                    }
                }
                val restoreProgress = catching { vbSession.machine.restoreSnapshot(snapshot) }
                    .ctx("Restoring VM snapshot")
                    .bind()
                withContext(Dispatchers.IO) {
                    catching {
                        restoreProgress.waitForCompletion(definition.launchTimeout.inWholeMilliseconds.toInt())
                    }.ctx("Failed to wait for VM to restore")
                        .bind()
                }
                ensure(restoreProgress.completed) {
                    Throwable("Restore progress was not completed")
                        .ctx("Restoring VM snapshot")
                }
                ensure(restoreProgress.resultCode == 0) {
                    Throwable("Result code was not 0, it was: ${restoreProgress.resultCode}")
                        .ctx("Restoring VM snapshot")
                }
                Logger.d { "VM restored" }
                val launchModeName = when (definition.launchMode) {
                    LaunchMode.GUI -> "gui"
                    LaunchMode.HEADLESS -> "headless"
                    LaunchMode.SDL -> "sdl"
                    LaunchMode.UNSPECIFIED -> null
                }
                catching(vbSession::unlockMachine)
                    .ctx("Unlocking VM")
                    .bind()
                awaitTrueSafe { vbMachine.sessionState == SessionState.Unlocked }
                    .ctx("VM session state was not unlocked after restore")
                    .bind()
                val launchProgress = catching {
                    vbMachine.launchVMProcess(vbSession, launchModeName, null)
                }.ctx("Launching VM")
                    .bind()
                withContext(Dispatchers.IO) {
                    catching {
                        launchProgress.waitForCompletion(definition.launchTimeout.inWholeMilliseconds.toInt())
                    }.ctx("Failed to wait for VM to start")
                        .bind()
                }
                awaitTrueSafe { vbMachine.sessionState == SessionState.Locked }
                    .ctx("VM session state was not locked after launch")
                    .bind()
                ensure(launchProgress.completed) {
                    Throwable("Launch progress was not completed")
                        .ctx("Launching VM")
                }
                ensure(launchProgress.resultCode == 0) {
                    Throwable("Result code was not 0, it was: ${launchProgress.resultCode}")
                        .ctx("Launching VM")
                }
                Logger.d { "VM started" }
            }
        }
    }

    override suspend fun stopVM() = either {
        resourceScope {
            withContext(Dispatchers.IO) {
                Logger.d { "Stopping VM" }
                val vbManager = init().bind()
                val vbSession = vbManager.sessionObject
                val vbox = vbManager.vBox
                val vbMachine = catching { vbox.findMachine(definition.vmName) }
                    .ctx("Finding VM")
                    .bind()
                Logger.d { "Found VM: ${vbMachine.name}" }
                installF(
                    {
                        catching { vbMachine.lockMachine(vbSession, LockType.Shared) }
                            .ctx("Locking VM")
                            .bind()
                    },
                    {
                        catching(vbSession::unlockMachine)
                            .ctx("Unlocking VM in release")
                            .bind()
                    }
                )
                val currentMachineStatus = catching { vbMachine.state }
                    .ctx("Getting VM state")
                    .bind()
                Logger.d { "Current VM state: $currentMachineStatus" }
                when (currentMachineStatus) {
                    MachineState.Running -> Unit
                    MachineState.PoweredOff -> return@withContext
                    MachineState.Saved,
                    MachineState.AbortedSaved -> catching {
                        vbSession.machine.discardSavedState(true)
                    }.ctx("Discarding VM state due to it being: $currentMachineStatus")
                        .bind()

                    else -> {
                        Logger.w { "VM is in an incorrect state: $currentMachineStatus" }
                    }
                }
                val stopProgress = catching(vbSession.console::powerDown)
                    .ctx("Powering down VM")
                    .bind()
                withContext(Dispatchers.IO) {
                    catching {
                        stopProgress.waitForCompletion(definition.launchTimeout.inWholeMilliseconds.toInt())
                    }.ctx("Failed to wait for VM to stop")
                        .bind()
                }
                ensure(stopProgress.resultCode == 0) {
                    Throwable("VM failed to stop: ${stopProgress.errorInfo.text}")
                }
                Logger.d { "VM stopped" }
            }
        }
    }

    private suspend fun ResourceScope.init(): Either<Throwable, VirtualBoxManager> = either {
        installF(
            {
                catching { VirtualBoxManager.createInstance(null) }
                    .ctx("Creating VirtualBoxManager")
                    .bind()
            },
            {
                catching { it.cleanup() }
                    .ctx("Cleaning up VirtualBoxManager")
                    .bind()
            }
        ).apply {
            catching { connect(definition.endpoint.toString(), definition.username, definition.password) }
                .ctx("Connecting to VirtualBox endpoint")
                .bind()
        }
    }

    private suspend fun awaitTrueSafe(
        maxRetries: Int = 50,
        retryDelay: Duration = 100.milliseconds,
        checkState: suspend () -> Boolean
    ): Either<Throwable, Unit> = Schedule
        .spaced<Throwable>(retryDelay)
        .and(Schedule.recurs(maxRetries.toLong()))
        .retryRaise {
            val state = catching { checkState() }
                .ctx("Check state kept throwing after exhausting the retries")
                .bind()
            ensure(state) {
                Throwable("State was not true after $maxRetries retries")
            }
        }

    enum class LaunchMode {
        GUI,
        HEADLESS,
        SDL,
        UNSPECIFIED
    }
}