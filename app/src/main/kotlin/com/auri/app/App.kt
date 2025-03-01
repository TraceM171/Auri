package com.auri.app

import com.auri.collection.CollectionService
import com.auri.common.data.sqliteConnection
import com.auri.common.withExtensions
import com.auri.conf.configByPrefix
import com.auri.conf.model.CollectionPhaseConfig
import com.auri.conf.model.MainConf
import com.auri.core.common.util.chainIfNotNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File

suspend fun collectSamples(
    configFile: File,
) = coroutineScope {
    val workingDirectory = File("/home/auri/TFM/auri/scratch")
    val auriDB = File(workingDirectory, "auri.db")

    val mainConfig: MainConf = configFile.configByPrefix()
    val classLoader = Thread.currentThread().contextClassLoader
        .chainIfNotNull(mainConfig.extensionsFolder) { withExtensions(it) }
    val phaseConfig: CollectionPhaseConfig =
        configFile.configByPrefix(classLoader = classLoader, prefix = "collectionPhase")

    val collectionService = CollectionService(
        workingDirectory = workingDirectory,
        invalidateCache = false,
        auriDB = auriDB.let(::sqliteConnection),
        collectors = phaseConfig.collectors,
    )
    launch {
        collectionService.collectionStatus.collect {
            println(it)
        }
    }
    launch { collectionService.run() }
}