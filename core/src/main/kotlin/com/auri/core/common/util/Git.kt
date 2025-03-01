package com.auri.core.common.util

import arrow.core.raise.either
import co.touchlab.kermit.Logger
import java.io.File
import java.net.URL

data class GitRepo(
    val url: URL,
    val branch: String,
    val commit: String?
)

suspend fun cloneRepo(
    workingDirectory: File,
    gitRepo: GitRepo
) = either {
    val repoFolder = File(
        workingDirectory,
        gitRepo.url.path.substringAfterLast("/").substringBeforeLast(".")
    ).apply { mkdirs() }
    val repoRoot = runNativeCommand(
        workingDir = repoFolder,
        "git",
        "rev-parse",
        "--show-toplevel"
    ).onLeft {
        Logger.e { "Failed to get repository root: $it" }
    }.bind()
    val existsRepo = File(repoRoot) == repoFolder

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
            return@either repoFolder
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
        return@either repoFolder
    }

    Logger.d { "Cloning repository (${gitRepo.url}) to $repoFolder" } // Repository does not exist
    runNativeCommand(
        workingDir = workingDirectory,
        "git",
        "clone",
        gitRepo.url.toString(),
        repoFolder.absolutePath,
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
    repoFolder
}