package com.auri.core.common.util

import com.auri.core.common.MissingDependency

object DependencyChecks {
    suspend fun checkGitAvailable(
        use: String
    ): MissingDependency? = runNativeCommand(
        workingDir = null,
        "git",
        "version"
    ).mapLeft {
        MissingDependency(
            name = "git",
            use = use,
            resolution = "Install git using your package manager or from https://git-scm.com/downloads",
        )
    }.swap().getOrNull()
}