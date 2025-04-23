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
import com.auri.core.common.util.failure
import com.auri.core.common.util.installF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.virtualbox_7_1.*
import java.net.URI
import java.net.URL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
                    failure("Snapshot is not online")
                }
                lockVM(vbSession, vbMachine)
                    .ctx("Locking VM")
                    .bind()
                val currentMachineStatus = catching { vbMachine.state }
                    .ctx("Getting VM state")
                    .bind()
                Logger.d { "Current VM state: $currentMachineStatus" }
                if (currentMachineStatus != MachineState.PoweredOff) {
                    Logger.d { "VM is not powered off, trying to power it off first" }
                    powerOffVM(vbSession, vbMachine)
                        .ctx("Powering off VM due to it being running")
                        .bind()
                    lockVM(vbSession, vbMachine)
                        .ctx("Locking VM after powering it off")
                        .bind()
                }
                awaitTrueSafe(timeout = 3.seconds) { vbSession.state == SessionState.Locked }
                    .ctx("Session state was not locked after lock, it was: ${vbSession.state}")
                    .bind()
                val restoreProgress = catching { vbSession.machine.restoreSnapshot(snapshot) }
                    .ctx("Restoring VM snapshot")
                    .bind()
                catching { restoreProgress.waitForCompletion(definition.launchTimeout.inWholeMilliseconds.toInt()) }
                    .ctx("Failed to wait for VM to restore")
                    .bind()
                ensure(restoreProgress.completed) {
                    Throwable("Restore progress was not completed")
                        .ctx("Restoring VM snapshot")
                }
                ensure(restoreProgress.resultCode == 0) {
                    Throwable("Result code was not 0, it was: ${restoreProgress.resultCode}, error: ${restoreProgress.errorInfo?.text}")
                        .ctx("Restoring VM snapshot")
                }
                awaitTrueSafe(timeout = 8.seconds) { vbMachine.state == MachineState.Saved }
                    .ctx("VM state was not saved after restore. Restore process error: ${restoreProgress.errorInfo?.text}")
                    .bind()
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
                awaitTrueSafe { vbSession.state == SessionState.Unlocked }
                    .ctx("VM session state was not unlocked after restore, it was: ${vbSession.state}")
                    .bind()
                val launchProgress = catching { vbMachine.launchVMProcess(vbSession, launchModeName, null) }
                    .ctx("Launching VM")
                    .bind()
                catching { launchProgress.waitForCompletion(definition.launchTimeout.inWholeMilliseconds.toInt()) }
                    .ctx("Failed to wait for VM to start")
                    .bind()
                ensure(launchProgress.completed) {
                    failure("Launch progress was not completed")
                }
                ensure(launchProgress.resultCode == 0) {
                    failure("Launch result code was not 0, it was: ${launchProgress.resultCode}, error: ${launchProgress.errorInfo?.text}")
                }
                awaitTrueSafe(timeout = 10.seconds) { vbMachine.state == MachineState.Running }
                    .ctx("VM state was not running after launch, it was: ${vbMachine.state}")
                    .bind()
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
                lockVM(vbSession, vbMachine)
                    .ctx("Locking VM")
                    .bind()
                powerOffVM(vbSession, vbMachine)
                    .ctx("Powering off VM")
                    .bind()
            }
        }
    }

    private suspend fun powerOffVM(
        vbSession: ISession,
        vbMachine: IMachine,
    ) = either {
        suspend fun powerOff() = either {
            Logger.d { "Powering down VM" }
            val stopProgress = catching { vbSession.console.powerDown() }
                .ctx("Powering down VM")
                .bind()
            catching { stopProgress.waitForCompletion(definition.launchTimeout.inWholeMilliseconds.toInt()) }
                .ctx("Failed to wait for VM to stop")
                .bind()
            ensure(stopProgress.completed) {
                failure("Stop progress was not completed")
            }
            ensure(stopProgress.resultCode == 0) {
                failure("Stop result code was not 0, it was: ${stopProgress.resultCode}, error: ${stopProgress.errorInfo?.text}")
            }
            awaitTrueSafe(timeout = 8.seconds) { vbMachine.state == MachineState.PoweredOff }
                .ctx("VM session state was not powered off after stop, it was: ${vbMachine.state}")
                .bind()
            awaitTrueSafe(timeout = 5.seconds) { vbMachine.sessionState == SessionState.Unlocked }
                .ctx("VM session state was not unlocked after stop, it was: ${vbMachine.sessionState}")
                .bind()
            Logger.d { "VM stopped" }
        }

        val currentMachineStatus = catching { vbMachine.state }
            .ctx("Getting VM state to check if already stopped")
            .bind()
        Logger.d { "Current VM state: $currentMachineStatus" }
        when (currentMachineStatus) {
            MachineState.PoweredOff -> {
                Logger.d { "VM is already powered off" }
                return@either
            }

            MachineState.Saved,
            MachineState.AbortedSaved -> {
                Logger.d { "VM is $currentMachineStatus, discarding state" }
                catching { vbSession.machine.discardSavedState(true) }
                    .ctx("Discarding VM state due to it being: $currentMachineStatus")
                    .bind()
                awaitTrueSafe(timeout = 5.seconds) { vbSession.machine.state == MachineState.PoweredOff }
                    .ctx("VM state was not powered off after discarding state, it was: ${vbSession.machine.state}")
                    .bind()
            }

            MachineState.Running -> {
                Logger.d { "VM is running, powering it off" }
                powerOff()
                    .ctx("Powering off VM due to it being running")
                    .bind()
            }

            else -> {
                Logger.w { "VM is in an incorrect state: $currentMachineStatus, trying to stop it" }
                powerOff()
                    .ctx("Powering off VM due to it being in an incorrect state")
                    .bind()
            }
        }
    }

    private suspend fun ResourceScope.lockVM(
        vbSession: ISession,
        vbMachine: IMachine,
    ) = either {
        if (vbSession.state == SessionState.Locked) {
            Logger.d { "VM is already locked" }
            return@either
        }
        awaitTrueSafe { vbSession.state == SessionState.Unlocked }
            .ctx("VM session state was not unlocked before lock, it was: ${vbSession.state}")
            .bind()
        Logger.d { "VM is not locked, locking it" }
        installF(
            {
                catching { vbMachine.lockMachine(vbSession, LockType.Shared) }
                    .ctx("Locking VM")
                    .bind()
            },
            {
                if (vbSession.state == SessionState.Unlocked) return@installF
                catching(vbSession::unlockMachine)
                    .ctx("Unlocking VM in release")
                    .bind()
                awaitTrueSafe(timeout = 2.seconds) { vbSession.state == SessionState.Unlocked }
                    .ctx("VM session state was not unlocked after release, it was: ${vbMachine.sessionState}")
                    .bind()
            }
        )
        awaitTrueSafe(timeout = 3.seconds) { vbSession.state == SessionState.Locked }
            .ctx("Session state was not locked after lock, it was: ${vbSession.state}")
            .bind()
    }

    private suspend fun ResourceScope.init(): Either<Throwable, VirtualBoxManager> = either {
        installF(
            {
                catching { VirtualBoxManager.createInstance(null) }
                    .ctx("Creating VirtualBoxManager")
                    .bind()
            },
            {
                catching(it::cleanup)
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
        timeout: Duration = 5.seconds,
        retryDelay: Duration = 100.milliseconds,
        checkState: suspend () -> Boolean
    ): Either<Throwable, Unit> {
        val startMark = TimeSource.Monotonic.markNow()
        var retries = 0
        return Schedule
            .spaced<Throwable>(retryDelay)
            .doWhile { _, _ -> startMark.elapsedNow() < timeout }
            .retryRaise {
                retries++
                val state = catching { checkState() }
                    .ctx("Check state kept throwing after exhausting the retries")
                    .bind()
                ensure(state) {
                    failure("State was not true after $timeout (retries: $retries)")
                }
            }
    }

    enum class LaunchMode {
        GUI,
        HEADLESS,
        SDL,
        UNSPECIFIED
    }
}