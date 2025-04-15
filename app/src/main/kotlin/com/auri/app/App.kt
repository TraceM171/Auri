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
import com.auri.app.common.data.entity.SampleLivenessCheckTable
import com.auri.app.common.data.sqliteConnection
import com.auri.app.common.withExtensions
import com.auri.app.conf.configByPrefix
import com.auri.app.conf.model.CollectionPhaseConfig
import com.auri.app.conf.model.LivenessPhaseConfig
import com.auri.app.conf.model.MainConf
import com.auri.app.liveness.LivenessProcessStatus
import com.auri.app.liveness.LivenessService
import com.auri.core.analysis.Analyzer
import com.auri.core.collection.Collector
import com.auri.core.collection.InfoProvider
import com.auri.core.common.util.chainIfNotNull
import com.auri.core.common.util.messageWithCtx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.nio.file.Path
import kotlin.io.path.*

suspend fun CoroutineScope.launchSampleCollection(
    baseDirectory: Path,
    runbook: Path,
    minLogSeverity: Severity,
    pruneCache: Boolean
): Flow<CollectionProcessStatus> {
    val initContext = init(
        baseDirectory = baseDirectory,
        runbook = runbook,
        minLogSeverity = minLogSeverity,
        actionName = "collection",
        pruneCache = pruneCache
    ).getOrElse {
        return when (it) {
            is InitError.GettingMainConfig -> flowOf(
                CollectionProcessStatus.Failed(
                    what = "Failed to load main segment of runbook",
                    why = it.cause.messageWithCtx ?: "Unknown error"
                )
            )
        }
    }

    val phaseConfig = runbook.configByPrefix<CollectionPhaseConfig>(
        classLoader = initContext.classLoader,
        prefix = "collectionPhase"
    ).getOrElse {
        return flowOf(
            CollectionProcessStatus.Failed(
                what = "Failed to load collection phase segment of runbook",
                why = it.messageWithCtx ?: "Unknown error"
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
    val job = launch { collectionService.run() }

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
    }.onCompletion {
        job.cancel()
        phaseConfig.run {
            collectors.forEach(Collector::close)
            infoProviders.forEach(InfoProvider::close)
        }
    }
}

suspend fun CoroutineScope.launchLivenessAnalysis(
    baseDirectory: Path,
    runbook: Path,
    minLogSeverity: Severity,
    pruneCache: Boolean,
    streamed: Boolean
): Flow<LivenessProcessStatus> {
    val initContext = init(
        baseDirectory = baseDirectory,
        runbook = runbook,
        actionName = "liveness",
        minLogSeverity = minLogSeverity,
        pruneCache = pruneCache
    ).getOrElse {
        return when (it) {
            is InitError.GettingMainConfig -> flowOf(
                LivenessProcessStatus.Failed(
                    what = "Failed to load main segment of runbook",
                    why = it.cause.messageWithCtx ?: "Unknown error"
                )
            )
        }
    }

    val phaseConfig = runbook.configByPrefix<LivenessPhaseConfig>(
        classLoader = initContext.classLoader,
        prefix = "livenessPhase"
    ).getOrElse {
        return flowOf(
            LivenessProcessStatus.Failed(
                what = "loading liveness phase segment of runbook",
                why = it.messageWithCtx ?: "Unknown error"
            )
        )
    }

    val livenessService = LivenessService(
        cacheDir = initContext.cacheDir,
        samplesDir = initContext.samplesDir,
        auriDB = initContext.auriDB,
        sampleExecutionPath = phaseConfig.sampleExecutionPath,
        vmManager = phaseConfig.vmManager,
        vmInteraction = phaseConfig.vmInteraction,
        analyzers = phaseConfig.analyzers,
        markAsChangedOnAccessLost = phaseConfig.markAsChangedOnAccessLost,
        markAsInactiveAfter = phaseConfig.markAsInactiveAfter,
        analyzeEvery = phaseConfig.analyzeEvery,
        keepListening = phaseConfig.keepListening.takeIf { streamed },
    )
    val job = launch { livenessService.run() }


    return livenessService.analysisStatus.transformWhile {
        emit(it)

        when (it) {
            is LivenessProcessStatus.Analyzing,
            LivenessProcessStatus.Initializing,
            is LivenessProcessStatus.CapturingGoodState,
            LivenessProcessStatus.NotStarted -> true

            is LivenessProcessStatus.Finished,
            is LivenessProcessStatus.MissingDependencies,
            is LivenessProcessStatus.Failed -> false
        }
    }.onCompletion {
        job.cancel()
        phaseConfig.run {
            vmManager.close()
            vmInteraction.close()
            analyzers.forEach(Analyzer::close)
        }
    }
}


@OptIn(ExperimentalPathApi::class)
private suspend fun init(
    baseDirectory: Path,
    runbook: Path,
    minLogSeverity: Severity,
    actionName: String,
    pruneCache: Boolean
) = either<InitError, InitContext> {
    val cacheDir = baseDirectory.resolve("cache/collection")
    val samplesDir = baseDirectory.resolve("samples")
    val extensionsDir = baseDirectory.resolve("extensions")
    val logsFolder = baseDirectory.resolve("logs")
    val logsFile = logsFolder.resolve(actionName)
    val auriDB = baseDirectory.resolve("auri.db").let(::sqliteConnection)

    Logger.run {
        setLogWriters(
            RollingFileLogWriter(
                config = RollingFileLogWriterConfig(
                    logFilePath = kotlinx.io.files.Path(logsFolder.pathString),
                    logFileName = logsFile.name,
                    maxLogFiles = 1,
                    logTag = true,
                    prependTimestamp = true,
                ),
                messageStringFormatter = DefaultMessageFormatter("com.auri")
            )
        )
        setMinSeverity(Severity.Info)
        i { "\n\n*** Starting Auri ***\n" }
        setMinSeverity(minLogSeverity)
    }

    val mainConfig = withError({ InitError.GettingMainConfig(it) }) {
        runbook.configByPrefix<MainConf>().bind()
    }
    val classLoader = Thread.currentThread().contextClassLoader
        .chainIfNotNull(extensionsDir) { withExtensions(it) }

    if (pruneCache) {
        cacheDir.deleteRecursively()
    }

    baseDirectory.createDirectories()
    cacheDir.createDirectories()
    samplesDir.createDirectories()
    extensionsDir.createDirectories()
    newSuspendedTransaction(context = Dispatchers.IO, db = auriDB) {
        SchemaUtils.create(
            RawSampleTable,
            SampleInfoTable,
            SampleLivenessCheckTable
        )
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
    val cacheDir: Path,
    val samplesDir: Path,
    val auriDB: Database,
    val mainConfig: MainConf,
    val classLoader: ClassLoader
)

private sealed interface InitError {
    data class GettingMainConfig(val cause: Throwable) : InitError
}