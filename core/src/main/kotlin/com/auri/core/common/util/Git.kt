package com.auri.core.common.util

import arrow.core.raise.either
import co.touchlab.kermit.Logger
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.*

data class GitRepo(
    val url: URL,
    val branch: String,
    val commit: String?
)

suspend fun cloneRepo(
    workingDirectory: Path,
    gitRepo: GitRepo
) = either {
    val repoFolder = getRepoFolder(
        workingDirectory,
        gitRepo
    )
    val repoRoot = runNativeCommand(
        workingDir = repoFolder,
        "git",
        "rev-parse",
        "--show-toplevel"
    ).onLeft {
        Logger.e { "Failed to get repository root: $it" }
    }.bind()
    val existsRepo = Path(repoRoot) == repoFolder

    if (existsRepo) { // Repository already exists
        Logger.d { "Repository already exists, checking if updated" }
        runNativeCommand(
            workingDir = repoFolder,
            "git",
            "fetch",
            "origin",
            gitRepo.branch
        ).onLeft {
            Logger.e { "Failed to fetch repository: $it" }
        }.bind()
        Logger.d { "Successfully fetched repository" }
        val currentCommit = runNativeCommand(
            workingDir = repoFolder,
            "git",
            "rev-parse",
            "HEAD"
        ).onLeft {
            Logger.e { "Failed to get current commit of repository: $it" }
        }.bind()
        val remoteCommit = runNativeCommand(
            workingDir = repoFolder,
            "git",
            "rev-parse",
            "origin/${gitRepo.branch}"
        ).onLeft {
            Logger.e { "Failed to get remote commit of repository: $it" }
        }.bind()
        if (currentCommit == remoteCommit || gitRepo.commit == currentCommit) { // Repository is up to date or at the specified commit
            Logger.d { "Repository is up to date" }
            return@either CloneRepoResult.NotChanged(repoFolder)
        }
        Logger.d { "Repository is outdated, updating" } // Repository is outdated
        runNativeCommand(
            workingDir = repoFolder,
            "git",
            "reset",
            "--hard",
            "origin/${gitRepo.branch}"
        ).onLeft {
            Logger.e { "Failed to reset repository: $it" }
        }.bind()
        Logger.d { "Successfully updated repository" }
        return@either CloneRepoResult.Updated(repoFolder)
    }

    Logger.d { "Cloning repository (${gitRepo.url}) to $repoFolder" } // Repository does not exist
    runNativeCommand(
        workingDir = workingDirectory,
        "git",
        "clone",
        gitRepo.url.toString(),
        repoFolder.absolutePathString(),
        "--branch",
        gitRepo.branch,
        "--single-branch",
        "--depth",
        "1"
    ).onLeft {
        Logger.e { "Failed to clone repository: $it" }
    }.bind()
    Logger.d { "Successfully cloned repository" }
    if (gitRepo.commit != null) {
        Logger.d { "Checking out commit ${gitRepo.commit}" }
        runNativeCommand(
            workingDir = repoFolder,
            "git",
            "checkout",
            gitRepo.commit
        ).onLeft {
            Logger.e { "Failed to checkout commit: $it" }
        }.bind()
        Logger.d { "Successfully checked out commit" }
    }
    return@either CloneRepoResult.Cloned(repoFolder)
}

@OptIn(ExperimentalPathApi::class)
suspend fun cleanupRepo(
    workingDirectory: Path,
    gitRepo: GitRepo
) = either {
    val repoFolder = getRepoFolder(
        workingDirectory,
        gitRepo
    )
    val repoRoot = runNativeCommand(
        workingDir = repoFolder,
        "git",
        "rev-parse",
        "--show-toplevel"
    ).onLeft {
        Logger.e { "Failed to get repository root: $it" }
    }.bind()
    if (Path(repoRoot) != repoFolder) {
        Logger.d { "Repository does not exist, skipping cleanup" }
        return@either Unit
    }

    repoFolder.listDirectoryEntries()
        .filterNot { it.name.startsWith(".") }
        .forEach(Path::deleteRecursively)

    runNativeCommand(
        workingDir = repoFolder,
        "git",
        "checkout",
        "--detach"
    ).onLeft {
        Logger.e { "Failed to detach HEAD: $it" }
    }.bind()

    runNativeCommand(
        workingDir = repoFolder,
        "git",
        "update-ref",
        "-d",
        "HEAD"
    ).onLeft {
        Logger.e { "Failed to update ref: $it" }
    }.bind()

    runNativeCommand(
        workingDir = repoFolder,
        "git",
        "reflog",
        "expire",
        "--expire=now",
        "--all"
    ).onLeft {
        Logger.e { "Failed to expire reflog: $it" }
    }.bind()

    runNativeCommand(
        workingDir = repoFolder,
        "git",
        "gc",
        "--prune=now",
        "--aggressive"
    ).onLeft {
        Logger.e { "Failed to garbage collect: $it" }
    }.bind()
}

private fun getRepoFolder(
    workingDirectory: Path,
    gitRepo: GitRepo
) = workingDirectory.resolve(
    gitRepo.url.path.substringAfterLast("/").substringBeforeLast(".")
).apply { createDirectories() }

sealed class CloneRepoResult(val repoFolder: Path) {
    data class NotChanged(val repoPath: Path) : CloneRepoResult(repoPath)
    data class Updated(val repoPath: Path) : CloneRepoResult(repoPath)
    data class Cloned(val repoPath: Path) : CloneRepoResult(repoPath)
}