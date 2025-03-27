package com.auri.core.analysis

import arrow.core.Either
import com.auri.core.common.ExtensionPoint
import com.auri.core.common.HasDependencies
import com.auri.core.common.MissingDependency

/**
 * Interface for Virtual Machine Manager.
 *
 * A VMManager is a component that manages the lifecycle of a virtual machine.
 */
@ExtensionPoint
interface VMManager : HasDependencies {
    /**
     * The name of the VMManager. Must be unique for each VMManager.
     */
    val name: String

    /**
     * A description of the VMManager.
     */
    val description: String

    /**
     * The version of the VMManager.
     */
    val version: String

    /**
     * Launches the virtual machine.
     *
     * @return Either a left value if the VM could not be launched, or a right value if the VM was launched successfully.
     */
    suspend fun launchVM(): Either<Unit, Unit>

    /**
     * Stops the virtual machine.
     *
     * @return Either a left value if the VM could not be stopped, or a right value if the VM was stopped successfully.
     */
    suspend fun stopVM(): Either<Unit, Unit>

    override suspend fun checkDependencies(): List<MissingDependency> = emptyList()
}