package com.auri.core.common.util

import arrow.core.raise.either
import co.touchlab.kermit.Logger
import com.auri.core.collection.Collector
import java.io.File
import java.net.URL

data class GitRepo(
    val url: URL,
    val branch: String,
    val commit: String?
)

suspend fun Collector.cloneRepo(
    collectionParameters: Collector.CollectionParameters,
    gitRepo: GitRepo
) = either {
    val repoFolder = File(
        collectionParameters.workingDirectory,
        gitRepo.url.path.substringAfterLast("/").substringBeforeLast(".")
    ).apply { mkdirs() }
    val repoRoot = runNativeCommand(
        workingDir = repoFolder,
        "git",
        "rev-parse",
        "--show-toplevel"
    ).onLeft {
        Logger.e { "Failed to get repository root: $it" }
    }.mapLeft { }.bind()
    val existsRepo = File(repoRoot) == repoFolder

    if (existsRepo) { // Repository already exists
        Logger.d { "$name repository already exists, checking if updated" }
        runNativeCommand(
            workingDir = repoFolder,
            "git",
            "fetch",
            "origin",
            gitRepo.branch
        ).onLeft {
            Logger.e { "Failed to fetch $name repository: $it" }
        }.mapLeft { }.bind()
        Logger.d { "Successfully fetched $name repository" }
        val currentCommit = runNativeCommand(
            workingDir = repoFolder,
            "git",
            "rev-parse",
            "HEAD"
        ).onLeft {
            Logger.e { "Failed to get current commit of $name repository: $it" }
        }.mapLeft { }.bind()
        val remoteCommit = runNativeCommand(
            workingDir = repoFolder,
            "git",
            "rev-parse",
            "origin/${gitRepo.branch}"
        ).onLeft {
            Logger.e { "Failed to get remote commit of $name repository: $it" }
        }.mapLeft { }.bind()
        if (currentCommit == remoteCommit || gitRepo.commit == currentCommit) { // Repository is up to date or at the specified commit
            Logger.d { "$name repository is up to date" }
            return@either repoFolder
        }
        Logger.d { "$name repository is outdated, updating" } // Repository is outdated
        runNativeCommand(
            workingDir = repoFolder,
            "git",
            "reset",
            "--hard",
            "origin/${gitRepo.branch}"
        ).onLeft {
            Logger.e { "Failed to reset $name repository: $it" }
        }.mapLeft { }.bind()
        Logger.d { "Successfully updated $name repository" }
        return@either repoFolder
    }

    Logger.d { "Cloning $name repository (${gitRepo.url}) to $repoFolder" } // Repository does not exist
    runNativeCommand(
        workingDir = collectionParameters.workingDirectory,
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
        Logger.e { "Failed to clone $name repository: $it" }
    }.mapLeft { }.bind()
    Logger.d { "Successfully cloned $name repository" }
    if (gitRepo.commit != null) {
        Logger.d { "Checking out commit ${gitRepo.commit}" }
        runNativeCommand(
            workingDir = repoFolder,
            "git",
            "checkout",
            gitRepo.commit
        ).onLeft {
            Logger.e { "Failed to checkout commit: $it" }
        }.mapLeft { }.bind()
        Logger.d { "Successfully checked out commit" }
    }
    repoFolder
}