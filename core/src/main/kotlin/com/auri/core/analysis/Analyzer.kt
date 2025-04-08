package com.auri.core.analysis

import arrow.core.Either
import com.auri.core.common.ExtensionPoint
import com.auri.core.common.HasDependencies
import com.auri.core.common.MissingDependency
import java.nio.file.Path

@ExtensionPoint
interface Analyzer : HasDependencies {
    val name: String
    val description: String
    val version: String

    suspend fun captureInitialState(
        workingDirectory: Path,
        interaction: VMInteraction
    ): Either<Unit, Unit>

    suspend fun reportChanges(
        workingDirectory: Path,
        interaction: VMInteraction
    ): Either<Unit, ChangeReport>

    override suspend fun checkDependencies(): List<MissingDependency> = emptyList()
}