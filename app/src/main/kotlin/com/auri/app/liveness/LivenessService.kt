package com.auri.app.liveness

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.toNonEmptyListOrNull
import arrow.fx.coroutines.parMapNotNull
import arrow.resilience.Schedule
import arrow.resilience.retryEither
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.auri.app.common.data.KeepListening
import com.auri.app.common.data.entity.RawSampleEntity
import com.auri.app.common.data.entity.RawSampleTable
import com.auri.app.common.data.entity.SampleLivenessCheckEntity
import com.auri.app.common.data.entity.SampleLivenessCheckTable
import com.auri.app.common.data.getAsFlow
import com.auri.core.analysis.Analyzer
import com.auri.core.analysis.ChangeReport
import com.auri.core.analysis.VMInteraction
import com.auri.core.analysis.VMManager
import com.auri.core.common.util.ctx
import com.auri.core.common.util.messageWithCtx
import com.auri.core.common.util.onLeftLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.notExists
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal class LivenessService(
    private val cacheDir: Path,
    private val samplesDir: Path,
    private val auriDB: Database,
    private val sampleExecutionPath: Path,
    private val vmManager: VMManager,
    private val vmInteraction: VMInteraction,
    private val analyzers: List<Analyzer>,
    private val markAsChangedOnAccessLost: Boolean,
    private val markAsInactiveAfter: Duration,
    private val analyzeEvery: Duration,
    private val keepListening: KeepListening?
) {
    private val _analysisStatus: MutableStateFlow<LivenessProcessStatus> =
        MutableStateFlow(LivenessProcessStatus.NotStarted)
    val analysisStatus = _analysisStatus.asStateFlow()

    suspend fun run() {
        _analysisStatus.update { LivenessProcessStatus.Initializing }
        val missingDependencies = analyzers.parMapNotNull { analyzer ->
            analyzer.checkDependencies().toNonEmptyListOrNull()?.let { analyzer to it }
        }.associate { it }
        if (missingDependencies.isNotEmpty()) {
            _analysisStatus.update { LivenessProcessStatus.MissingDependencies(missingDependencies) }
            return
        }
        val samplesFilterQuery = notExists(
            SampleLivenessCheckTable.selectAll()
                .where { SampleLivenessCheckTable.sampleId eq RawSampleTable.id }
        )
        val getTotalSamples = suspend {
            newSuspendedTransaction(
                context = Dispatchers.IO,
                db = auriDB
            ) { RawSampleEntity.count(samplesFilterQuery).toInt() }
        }
        val allSamples = RawSampleEntity.getAsFlow(
            database = auriDB,
            filter = { samplesFilterQuery },
            batchSize = 100,
            keepListening = keepListening
        )

        _analysisStatus.update { LivenessProcessStatus.CapturingGoodState(LivenessProcessStatus.CapturingGoodState.Step.StartingVM) }
        retryingOperation {
            vmManager.launchVM()
                .ctx("Launching VM")
                .ctx("Capturing initial state")
                .onLeftLog(severity = Severity.Warn, onlyMessage = true)
        }.onLeftLog()
            .getOrElse { error ->
                _analysisStatus.update {
                    LivenessProcessStatus.Failed(
                        what = "launch VM",
                        why = error.messageWithCtx ?: "Unknown"
                    )
                }
                return
            }
        vmInteraction.awaitReady()
            .ctx("Waiting for VM to be ready")
            .ctx("Capturing initial state")
            .onLeftLog()
            .getOrElse { error ->
                _analysisStatus.update {
                    LivenessProcessStatus.Failed(
                        what = "wait for VM",
                        why = error.messageWithCtx ?: "Unknown"
                    )
                }
                return
            }
        Logger.d { "VM is ready" }
        analyzers.forEach { analyzer ->
            _analysisStatus.update {
                LivenessProcessStatus.CapturingGoodState(
                    LivenessProcessStatus.CapturingGoodState.Step.Capturing(
                        analyzer
                    )
                )
            }
            Logger.d { "Capturing initial state by analyzer ${analyzer.name}" }
            analyzer.captureInitialState(cacheDir, vmInteraction)
                .ctx("Capturing initial state by analyzer ${analyzer.name}")
                .ctx("Capturing initial state")
                .onLeftLog()
                .getOrElse { error ->
                    _analysisStatus.update {
                        LivenessProcessStatus.Failed(
                            what = "capture initial state by analyzer ${analyzer.name}",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    return
                }
        }
        Logger.d { "Captured initial state" }
        _analysisStatus.update { LivenessProcessStatus.CapturingGoodState(LivenessProcessStatus.CapturingGoodState.Step.StoppingVM) }
        Logger.d { "Stopping VM" }
        retryingOperation {
            vmManager.stopVM()
                .ctx("Stopping VM")
                .ctx("Capturing initial state")
                .onLeftLog(severity = Severity.Warn, onlyMessage = true)
        }.onLeftLog()
            .getOrElse { error ->
                _analysisStatus.update {
                    LivenessProcessStatus.Failed(
                        what = "stop VM",
                        why = error.messageWithCtx ?: "Unknown"
                    )
                }
                return
            }
        Logger.d { "Stopped VM" }
        Logger.d { "Initial state captured" }

        _analysisStatus.update {
            LivenessProcessStatus.Analyzing(
                runningNow = null,
                LivenessProcessStatus.LivenessStats(
                    samplesStatus = emptyMap(),
                    totalSamplesAnalyzed = 0,
                    totalSamples = getTotalSamples()
                )
            )
        }
        var collectionError = false
        allSamples.takeWhile { entity ->
            Logger.d { "Starting analysis for sample ${entity.name}" }
            val samplePath = samplesDir.resolve(entity.path)
            _analysisStatus.update {
                val analyzingOrNull = (it as? LivenessProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = LivenessProcessStatus.Analyzing.RunningNow(
                        sampleId = entity.id.value,
                        step = LivenessProcessStatus.Analyzing.RunningNow.Step.StartingVM
                    )
                )
            }
            retryingOperation {
                vmManager.launchVM()
                    .ctx("Launching VM")
                    .ctx("Analyzing sample ${entity.name}")
                    .onLeftLog(severity = Severity.Warn, onlyMessage = true)
            }.onLeftLog()
                .getOrElse { error ->
                    _analysisStatus.update {
                        LivenessProcessStatus.Failed(
                            what = "launch VM",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    collectionError = true
                    return@takeWhile false
                }
            Logger.d { "Launched VM" }
            Logger.d { "Waiting for VM to be ready" }
            vmInteraction.awaitReady()
                .ctx("Waiting for VM to be ready")
                .ctx("Analyzing sample ${entity.name}")
                .onLeftLog()
                .getOrElse { error ->
                    _analysisStatus.update {
                        LivenessProcessStatus.Failed(
                            what = "wait for VM",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    collectionError = true
                    return@takeWhile false
                }
            Logger.d { "VM is ready" }
            _analysisStatus.update {
                val analyzingOrNull = (it as? LivenessProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = analyzingOrNull.runningNow!!.copy(
                        step = LivenessProcessStatus.Analyzing.RunningNow.Step.SendingSample
                    )
                )
            }
            vmInteraction.sendFile(samplePath.inputStream().buffered(), sampleExecutionPath)
                .ctx("Sending file")
                .ctx("Analyzing sample ${entity.name}")
                .onLeftLog()
                .getOrElse { error ->
                    _analysisStatus.update {
                        LivenessProcessStatus.Failed(
                            what = "send file",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    collectionError = true
                    return@takeWhile false
                }
            Logger.d { "Sent file" }
            _analysisStatus.update {
                val analyzingOrNull = (it as? LivenessProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = analyzingOrNull.runningNow!!.copy(
                        step = LivenessProcessStatus.Analyzing.RunningNow.Step.LaunchingSampleProcess
                    )
                )
            }
            val launchSampleScriptPath = sampleExecutionPath.resolveSibling("launch.bat")
            val launchSampleScriptContents = """
                @echo off
                cd /d "${sampleExecutionPath.parent}"
                "${sampleExecutionPath.name}"
            """.trimIndent()
            vmInteraction.sendFile(
                launchSampleScriptContents.byteInputStream().buffered(),
                launchSampleScriptPath
            ).ctx("Sending launch script file")
                .ctx("Analyzing sample ${entity.name}")
                .onLeftLog()
                .getOrElse { error ->
                    _analysisStatus.update {
                        LivenessProcessStatus.Failed(
                            what = "send launch script file",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    collectionError = true
                    return@takeWhile false
                }
            vmInteraction.prepareCommand("""schtasks /CREATE /SC ONCE /TN "LaunchSample" /TR "$launchSampleScriptPath" /ST 00:00 /RL HIGHEST /F""")
                .run()
                .ctx("Preparing command")
                .ctx("Analyzing sample ${entity.name}")
                .onLeftLog()
                .getOrElse { error ->
                    _analysisStatus.update {
                        LivenessProcessStatus.Failed(
                            what = "prepare command",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    collectionError = true
                    return@takeWhile false
                }
            vmInteraction.prepareCommand("""schtasks /RUN /TN "LaunchSample"""")
                .run()
                .ctx("Running command")
                .ctx("Analyzing sample ${entity.name}")
                .onLeftLog()
                .getOrElse { error ->
                    _analysisStatus.update {
                        LivenessProcessStatus.Failed(
                            what = "run command",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    collectionError = true
                    return@takeWhile false
                }
            Logger.d { "Ran file" }
            _analysisStatus.update {
                val analyzingOrNull = (it as? LivenessProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = analyzingOrNull.runningNow!!.copy(
                        step = LivenessProcessStatus.Analyzing.RunningNow.Step.WaitingChanges(
                            sampleTimeout = Clock.System.now() + markAsInactiveAfter
                        )
                    )
                )
            }
            val detectionStart = TimeSource.Monotonic.markNow()
            val changeReport = withTimeoutOrNull(markAsInactiveAfter) {
                var lostAccessCount = 0
                Schedule.spaced<Unit>(analyzeEvery).retryEither {
                    analyzers.forEach { analyzer ->
                        Logger.d { "Analyzing with ${analyzer.name}" }
                        val report = analyzer.reportChanges(cacheDir, vmInteraction)
                            .getOrElse { ChangeReport.NotChanged }
                        Logger.d { "Analyzer ${analyzer.name} finished: $report" }
                        when (report) {
                            is ChangeReport.NotChanged -> Unit
                            is ChangeReport.Changed -> return@withTimeoutOrNull report
                            is ChangeReport.AccessLost -> {
                                lostAccessCount++
                                if (lostAccessCount >= 3) return@withTimeoutOrNull report
                            }
                        }
                    }
                    Unit.left()
                }
                if (lostAccessCount > 0) ChangeReport.AccessLost
                else ChangeReport.NotChanged
            }?.extended ?: ChangeReport.NotChanged.extended
            val detectionTime = detectionStart.elapsedNow()
            Logger.d { "Analysis for sample ${entity.name} finished, changes detected: $changeReport" }
            _analysisStatus.update {
                val analyzingOrNull = (it as? LivenessProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = analyzingOrNull.runningNow!!.copy(
                        step = LivenessProcessStatus.Analyzing.RunningNow.Step.SavingResults
                    )
                )
            }
            newSuspendedTransaction(context = Dispatchers.IO, db = auriDB) {
                SampleLivenessCheckEntity.new {
                    this.sampleId = entity.id
                    this.checkDate = LocalDate.now().toKotlinLocalDate()
                    this.timeToDetect = detectionTime
                    this.isAlive = changeReport.changeFound
                    this.isAliveReason = changeReport.changeReport.json
                }
            }
            _analysisStatus.update {
                val analyzingOrNull = (it as? LivenessProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = analyzingOrNull.runningNow!!.copy(
                        step = LivenessProcessStatus.Analyzing.RunningNow.Step.StoppingVM
                    )
                )
            }
            retryingOperation {
                vmManager.stopVM()
                    .ctx("Stopping VM")
                    .ctx("Analyzing sample ${entity.name}")
                    .onLeftLog(severity = Severity.Warn, onlyMessage = true)
            }.onLeftLog()
                .getOrElse { error ->
                    _analysisStatus.update {
                        LivenessProcessStatus.Failed(
                            what = "stop VM",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    collectionError = true
                    return@takeWhile false
                }
            Logger.d { "Stopped VM" }
            _analysisStatus.update {
                val analyzingOrNull = (it as? LivenessProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = null,
                    livenessStats = analyzingOrNull.livenessStats.copy(
                        samplesStatus = analyzingOrNull.livenessStats.samplesStatus + mapOf(
                            entity.id.value to changeReport
                        ),
                        totalSamplesAnalyzed = analyzingOrNull.livenessStats.totalSamplesAnalyzed + 1,
                        totalSamples = getTotalSamples()
                    )
                )
            }
            true
        }.collect()
        if (collectionError) return
        Logger.i { "Analysis finished" }
        _analysisStatus.update {
            val analyzingOrNull = (it as? LivenessProcessStatus.Analyzing ?: return@update it).livenessStats
            LivenessProcessStatus.Finished(
                LivenessProcessStatus.LivenessStats(
                    samplesStatus = analyzingOrNull.samplesStatus,
                    totalSamplesAnalyzed = analyzingOrNull.totalSamplesAnalyzed,
                    totalSamples = analyzingOrNull.totalSamples
                )
            )
        }
    }

    private val ChangeReport.json
        get() = when (this) {
            is ChangeReport.NotChanged -> ""
            is ChangeReport.Changed -> what.joinToString(separator = "\n")
            is ChangeReport.AccessLost -> "Access lost"
        }

    private val ChangeReport.extended
        get() = LivenessProcessStatus.ExtendedChangeReport(
            changeFound = when (this) {
                is ChangeReport.NotChanged -> false
                is ChangeReport.Changed -> true
                is ChangeReport.AccessLost -> markAsChangedOnAccessLost
            },
            changeReport = this
        )

    private suspend fun <L, R> retryingOperation(
        maxRetries: Int = 5,
        delay: Duration = 3.seconds,
        operation: suspend () -> Either<L, R>
    ) = Schedule.spaced<L>(delay)
        .and(Schedule.recurs(maxRetries.toLong()))
        .retryEither { operation() }
}