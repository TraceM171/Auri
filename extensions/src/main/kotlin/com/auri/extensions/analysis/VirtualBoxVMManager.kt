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
import com.auri.core.common.catching
import com.auri.core.common.ignore
import com.auri.core.common.util.catchLog
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
                val vbManager = init().ignore().bind()
                val vbSession = vbManager.sessionObject
                val vbox = vbManager.vBox
                val vbMachine = catchLog("Failed to find VM") { vbox.findMachine(definition.vmName) }
                Logger.d { "Found VM: ${vbMachine.name}" }
                val snapshot = catchLog("Failed to find VM snapshot") { vbMachine.findSnapshot(definition.vmSnapshot) }
                Logger.d { "Found snapshot: ${snapshot.name}" }
                ensure(snapshot.online) {
                    Logger.e { "Snapshot is not online" }
                    raise(Unit)
                }
                installF(
                    {
                        catchLog("Failed to lock VM")
                        { vbMachine.lockMachine(vbSession, LockType.Shared) }
                    },
                    { vbSession.unlockMachine() }
                )
                val currentMachineStatus = catchLog("Failed to get VM state") { vbMachine.state }
                Logger.d { "Current VM state: $currentMachineStatus" }
                when (currentMachineStatus) {
                    MachineState.PoweredOff -> Unit
                    MachineState.Saved,
                    MachineState.AbortedSaved -> catchLog("Failed to discard VM state")
                    { vbSession.machine.restoreSnapshot(snapshot) }

                    else -> {
                        Logger.e { "VM is not in a valid state to start" }
                        raise(Unit)
                    }
                }
                val restoreProgress = catchLog("Failed to restore VM snapshot")
                { vbSession.machine.restoreSnapshot(snapshot) }
                withContext(Dispatchers.IO) {
                    catchLog("Failed to wait for VM to restore")
                    { restoreProgress.waitForCompletion(definition.launchTimeout.inWholeMilliseconds.toInt()) }
                }
                ensure(restoreProgress.resultCode == 0) {
                    Logger.e { "VM failed to restore: ${restoreProgress.errorInfo.text}" }
                    raise(Unit)
                }
                Logger.d { "VM restored" }
                val launchModeName = when (definition.launchMode) {
                    LaunchMode.GUI -> "gui"
                    LaunchMode.HEADLESS -> "headless"
                    LaunchMode.SDL -> "sdl"
                    LaunchMode.UNSPECIFIED -> null
                }
                catchLog("Failed to unlock VM")
                { vbSession.unlockMachine() }
                awaitTrueSafe { vbMachine.sessionState == SessionState.Unlocked }.ignore()
                val launchProgress = catchLog("Failed to launch VM")
                { vbMachine.launchVMProcess(vbSession, launchModeName, null) }
                withContext(Dispatchers.IO) {
                    catchLog("Failed to wait for VM to start")
                    { launchProgress.waitForCompletion(definition.launchTimeout.inWholeMilliseconds.toInt()) }
                }
                ensure(launchProgress.completed) {
                    Logger.e { "VM failed to start: ${launchProgress.errorInfo.text}" }
                    raise(Unit)
                }
                ensure(launchProgress.resultCode == 0) {
                    Logger.e { "VM failed to start: ${launchProgress.errorInfo.text}" }
                    raise(Unit)
                }
                Logger.d { "VM started" }
            }
        }
    }

    override suspend fun stopVM() = either {
        resourceScope {
            withContext(Dispatchers.IO) {
                Logger.d { "Stopping VM" }
                val vbManager = init().ignore().bind()
                val vbSession = vbManager.sessionObject
                val vbox = vbManager.vBox
                val vbMachine = catchLog("Failed to find VM")
                { vbox.findMachine(definition.vmName) }
                Logger.d { "Found VM: ${vbMachine.name}" }
                installF(
                    {
                        catchLog("Failed to lock VM")
                        { vbMachine.lockMachine(vbSession, LockType.Shared) }
                    },
                    { it -> vbSession.unlockMachine() }
                )
                val currentMachineStatus = catchLog("Failed to get VM state")
                { vbMachine.state }
                Logger.d { "Current VM state: $currentMachineStatus" }
                when (currentMachineStatus) {
                    MachineState.Running -> Unit
                    MachineState.PoweredOff -> return@withContext
                    MachineState.Saved,
                    MachineState.AbortedSaved -> catchLog("Failed to discard VM state")
                    { vbSession.machine.discardSavedState(true) }

                    else -> {
                        Logger.e { "VM is not in a valid state to stop" }
                        raise(Unit)
                    }
                }
                val stopProgress = catchLog("Failed to save VM state")
                { vbSession.console.powerDown() }
                withContext(Dispatchers.IO) {
                    catchLog("Failed to wait for VM to stop")
                    { stopProgress.waitForCompletion(definition.launchTimeout.inWholeMilliseconds.toInt()) }
                }
                ensure(stopProgress.resultCode == 0) {
                    Logger.e { "VM failed to stop: ${stopProgress.errorInfo.text}" }
                    raise(Unit)
                }
                Logger.d { "VM stopped" }
            }
        }
    }

    private suspend fun ResourceScope.init(): Either<Throwable, VirtualBoxManager> = either {
        installF(
            {
                catching("Failed to create VirtualBoxManager instance") { VirtualBoxManager.createInstance(null) }
            },
            {
                catching("Failed to disconnect VirtualBoxManager", it::disconnect)
            }
        ).apply {
            catching("Failed to connect to VirtualBox endpoint")
            { connect(definition.endpoint.toString(), definition.username, definition.password) }
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
            val state = catching("Value kept throwing after exhausting the retries") { checkState() }
            ensure(state) { Throwable("Value did not become true after exhausting the retries") }
        }

    enum class LaunchMode {
        GUI,
        HEADLESS,
        SDL,
        UNSPECIFIED
    }
}