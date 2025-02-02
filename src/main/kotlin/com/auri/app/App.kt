package com.auri.app

import com.auri.collection.CollectionService
import com.auri.conf.SubclassDecoder
import com.auri.conf.model.CollectionPhaseConfig
import com.auri.core.data.sqliteConnection
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.addFileSource
import java.io.File

@OptIn(ExperimentalHoplite::class)
suspend fun collectSamples(
    configFile: File,
) {
    val workingDirectory = File("/home/auri/TFM/auri/scratch")
    val auriDB = File(workingDirectory, "auri.db")

    //val mainConfig: MainConf = configFile.configByPrefix(prefix = null)
    val collectionPhaseConfig: CollectionPhaseConfig =
        ConfigLoaderBuilder.default()
            .addFileSource(configFile)
            .addDecoder(SubclassDecoder())
            .withExplicitSealedTypes()
            .build()
            .loadConfigOrThrow(prefix = "collectionPhase")
    val collectionService = CollectionService(
        workingDirectory = workingDirectory,
        invalidateCache = false,
        auriDB = auriDB.let(::sqliteConnection),
        collectors = collectionPhaseConfig.collectors,
    )
    collectionService.startCollection()
}