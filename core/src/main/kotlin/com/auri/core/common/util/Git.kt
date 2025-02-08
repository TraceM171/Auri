package com.auri.core.common.util

import arrow.core.raise.either
import co.touchlab.kermit.Logger
import com.auri.core.collection.model.Collector
import java.io.File
import java.net.URL

data class GitRepo(
    val url: URL,
    val branch: String
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
    ).getOrNull()
    val existsRepo = repoRoot?.let { File(it) } == repoFolder
    if (existsRepo) {
        if (collectionParameters.invalidateCache) {
            Logger.d { "Removing $name repository cache" }
            repoFolder.deleteRecursively()
        } else {
            Logger.d { "$name repository already exists" }
            return@either repoFolder
        }
    }
    Logger.d { "Cloning $name repository (${gitRepo.url}) to $repoFolder" }
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
    repoFolder
}