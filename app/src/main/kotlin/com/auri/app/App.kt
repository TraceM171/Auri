package com.auri.app

import arrow.core.getOrElse
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.auri.app.collection.CollectionProcessStatus
import com.auri.app.collection.CollectionService
import com.auri.app.common.data.entity.RawSampleTable
import com.auri.app.common.data.entity.SampleInfoTable
import com.auri.app.common.data.sqliteConnection
import com.auri.app.common.withExtensions
import com.auri.app.conf.configByPrefix
import com.auri.app.conf.model.CollectionPhaseConfig
import com.auri.app.conf.model.MainConf
import com.auri.core.common.util.chainIfNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.io.File

suspend fun CoroutineScope.launchSampleCollection(
    baseDirectory: File,
    runbook: File,
    pruneCache: Boolean
): Flow<CollectionProcessStatus> {
    val cacheDir = File(baseDirectory, "cache/collection")
    val samplesDir = File(baseDirectory, "samples")
    val extensionsDir = File(baseDirectory, "extensions")
    val auriDB = File(baseDirectory, "auri.db").let(::sqliteConnection)

    Logger.setMinSeverity(Severity.Warn) // TODO

    if (pruneCache) {
        cacheDir.deleteRecursively()
    }

    baseDirectory.mkdirs()
    cacheDir.mkdirs()
    samplesDir.mkdirs()
    extensionsDir.mkdirs()
    newSuspendedTransaction(context = Dispatchers.IO, db = auriDB) {
        SchemaUtils.create(RawSampleTable, SampleInfoTable)
    }

    runbook.configByPrefix<MainConf>().getOrElse {
        return MutableStateFlow(
            CollectionProcessStatus.Failed(
                what = "Failed to load main segment of runbook",
                why = it.message ?: "Unknown error"
            )
        )
    }
    val classLoader = Thread.currentThread().contextClassLoader
        .chainIfNotNull(extensionsDir) { withExtensions(it) }
    val phaseConfig =
        runbook.configByPrefix<CollectionPhaseConfig>(classLoader = classLoader, prefix = "collectionPhase").getOrElse {
            return MutableStateFlow(
                CollectionProcessStatus.Failed(
                    what = "Failed to load collection phase segment of runbook",
                    why = it.message ?: "Unknown error"
                )
            )
        }

    val collectionService = CollectionService(
        cacheDir = cacheDir,
        samplesDir = samplesDir,
        auriDB = auriDB,
        collectors = phaseConfig.collectors,
        infoProviders = phaseConfig.infoProviders
    )
    launch { collectionService.run() }

    return collectionService.collectionStatus.transformWhile {
        emit(it)

        when (it) {
            is CollectionProcessStatus.Collecting,
            CollectionProcessStatus.Initializing,
            CollectionProcessStatus.NotStarted -> true

            is CollectionProcessStatus.Finished,
            is CollectionProcessStatus.MissingDependencies,
            is CollectionProcessStatus.Failed -> false
        }
    }
}