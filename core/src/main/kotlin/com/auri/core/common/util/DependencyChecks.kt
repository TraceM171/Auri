package com.auri.core.common.util

import com.auri.core.common.MissingDependency

object DependencyChecks {
    suspend fun checkGitAvailable(
        neededTo: String
    ): MissingDependency? = runNativeCommand(
        workingDir = null,
        "git",
        "version"
    ).mapLeft {
        MissingDependency(
            name = "git",
            neededTo = neededTo,
            resolution = "install git using your package manager or from https://git-scm.com/downloads",
        )
    }.swap().getOrNull()
}