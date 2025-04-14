package com.auri.core.analysis

import arrow.core.Either
import com.auri.core.common.ExtensionPoint
import com.auri.core.common.HasDependencies
import com.auri.core.common.MissingDependency
import java.nio.file.Path

/**
 * Interface for analyzing changes in a virtual machine.
 *
 * This interface provides methods for capturing the initial state of the VM and reporting changes.
 */
@ExtensionPoint
interface Analyzer : HasDependencies, AutoCloseable {
    /**
     * The name of the analyzer. Must be unique for each analyzer.
     */
    val name: String

    /**
     * A description of the analyzer.
     */
    val description: String

    /**
     * The version of the analyzer.
     */
    val version: String

    /**
     * Captures the initial state of the virtual machine.
     *
     * @param workingDirectory The working directory for the analysis.
     * @param interaction The VM interaction object to use for communication with the VM.
     * @return An [Either] that represents the result of the operation.
     *        [Either.Left] if the operation failed, [Either.Right] if the operation succeeded.
     */
    suspend fun captureInitialState(
        workingDirectory: Path,
        interaction: VMInteraction
    ): Either<Throwable, Unit>

    /**
     * Reports the changes in the virtual machine.
     *
     * @param workingDirectory The working directory for the analysis.
     * @param interaction The VM interaction object to use for communication with the VM.
     * @return An [Either] that represents the result of the operation.
     *        [Either.Left] if the operation failed, [Either.Right] if the operation succeeded.
     */
    suspend fun reportChanges(
        workingDirectory: Path,
        interaction: VMInteraction
    ): Either<Throwable, ChangeReport>


    override suspend fun checkDependencies(): List<MissingDependency> = emptyList()

    override fun close() = Unit
}