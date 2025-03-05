package com.auri.app

import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.withError
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import com.auri.app.collection.CollectionProcessStatus
import com.auri.app.collection.CollectionService
import com.auri.app.common.DefaultMessageFormatter
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
import kotlinx.io.files.Path
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.io.File

suspend fun CoroutineScope.launchSampleCollection(
    baseDirectory: File,
    runbook: File,
    minLogSeverity: Severity,
    pruneCache: Boolean
): Flow<CollectionProcessStatus> {
    val initContext = init(
        baseDirectory = baseDirectory,
        runbook = runbook,
        minLogSeverity = minLogSeverity,
        pruneCache = pruneCache
    ).getOrElse {
        return when (it) {
            is InitError.GettingMainConfig -> MutableStateFlow(
                CollectionProcessStatus.Failed(
                    what = "Failed to load main segment of runbook",
                    why = it.cause.message ?: "Unknown error"
                )
            )
        }
    }

    val phaseConfig = runbook.configByPrefix<CollectionPhaseConfig>(
        classLoader = initContext.classLoader,
        prefix = "collectionPhase"
    ).getOrElse {
        return MutableStateFlow(
            CollectionProcessStatus.Failed(
                what = "Failed to load collection phase segment of runbook",
                why = it.message ?: "Unknown error"
            )
        )
    }

    val collectionService = CollectionService(
        cacheDir = initContext.cacheDir,
        samplesDir = initContext.samplesDir,
        auriDB = initContext.auriDB,
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


private suspend fun init(
    baseDirectory: File,
    runbook: File,
    minLogSeverity: Severity,
    pruneCache: Boolean
) = either<InitError, InitContext> {
    val cacheDir = File(baseDirectory, "cache/collection")
    val samplesDir = File(baseDirectory, "samples")
    val extensionsDir = File(baseDirectory, "extensions")
    val logsFile = File(baseDirectory, "auri")
    val auriDB = File(baseDirectory, "auri.db").let(::sqliteConnection)

    Logger.run {
        setMinSeverity(minLogSeverity)
        setLogWriters(
            RollingFileLogWriter(
                config = RollingFileLogWriterConfig(
                    logFileName = logsFile.name,
                    logFilePath = Path(logsFile.parent),
                ),
                messageStringFormatter = DefaultMessageFormatter("com.auri")
            )
        )
    }

    val mainConfig = withError({ InitError.GettingMainConfig(it) }) {
        runbook.configByPrefix<MainConf>().bind()
    }
    val classLoader = Thread.currentThread().contextClassLoader
        .chainIfNotNull(extensionsDir) { withExtensions(it) }

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

    InitContext(
        cacheDir = cacheDir,
        samplesDir = samplesDir,
        auriDB = auriDB,
        mainConfig = mainConfig,
        classLoader = classLoader
    )
}

private data class InitContext(
    val cacheDir: File,
    val samplesDir: File,
    val auriDB: Database,
    val mainConfig: MainConf,
    val classLoader: ClassLoader
)

private sealed interface InitError {
    data class GettingMainConfig(val cause: Throwable) : InitError
}