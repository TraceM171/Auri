package com.auri.app.analysis

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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal class AnalysisService(
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
    private val _analysisStatus: MutableStateFlow<AnalysisProcessStatus> =
        MutableStateFlow(AnalysisProcessStatus.NotStarted)
    val analysisStatus = _analysisStatus.asStateFlow()

    suspend fun run() {
        _analysisStatus.update { AnalysisProcessStatus.Initializing }
        val missingDependencies = analyzers.parMapNotNull { analyzer ->
            analyzer.checkDependencies().toNonEmptyListOrNull()?.let { analyzer to it }
        }.associate { it }
        if (missingDependencies.isNotEmpty()) {
            _analysisStatus.update { AnalysisProcessStatus.MissingDependencies(missingDependencies) }
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

        _analysisStatus.update { AnalysisProcessStatus.CapturingGoodState(AnalysisProcessStatus.CapturingGoodState.Step.StartingVM) }
        retryingOperation {
            vmManager.launchVM()
                .ctx("Launching VM")
                .ctx("Capturing initial state")
                .onLeftLog(severity = Severity.Warn, onlyMessage = true)
        }.onLeftLog()
            .getOrElse { error ->
                _analysisStatus.update {
                    AnalysisProcessStatus.Failed(
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
                    AnalysisProcessStatus.Failed(
                        what = "wait for VM",
                        why = error.messageWithCtx ?: "Unknown"
                    )
                }
                return
            }
        Logger.d { "VM is ready" }
        analyzers.forEach { analyzer ->
            _analysisStatus.update {
                AnalysisProcessStatus.CapturingGoodState(
                    AnalysisProcessStatus.CapturingGoodState.Step.Capturing(
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
                        AnalysisProcessStatus.Failed(
                            what = "capture initial state by analyzer ${analyzer.name}",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    return
                }
        }
        Logger.d { "Captured initial state" }
        _analysisStatus.update { AnalysisProcessStatus.CapturingGoodState(AnalysisProcessStatus.CapturingGoodState.Step.StoppingVM) }
        Logger.d { "Stopping VM" }
        retryingOperation {
            vmManager.stopVM()
                .ctx("Stopping VM")
                .ctx("Capturing initial state")
                .onLeftLog(severity = Severity.Warn, onlyMessage = true)
        }.onLeftLog()
            .getOrElse { error ->
                _analysisStatus.update {
                    AnalysisProcessStatus.Failed(
                        what = "stop VM",
                        why = error.messageWithCtx ?: "Unknown"
                    )
                }
                return
            }
        Logger.d { "Stopped VM" }
        Logger.d { "Initial state captured" }

        _analysisStatus.update {
            AnalysisProcessStatus.Analyzing(
                runningNow = null,
                AnalysisProcessStatus.AnalysisStats(
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
                val analyzingOrNull = (it as? AnalysisProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = AnalysisProcessStatus.Analyzing.RunningNow(
                        sampleId = entity.id.value,
                        step = AnalysisProcessStatus.Analyzing.RunningNow.Step.StartingVM
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
                        AnalysisProcessStatus.Failed(
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
                        AnalysisProcessStatus.Failed(
                            what = "wait for VM",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    collectionError = true
                    return@takeWhile false
                }
            Logger.d { "VM is ready" }
            _analysisStatus.update {
                val analyzingOrNull = (it as? AnalysisProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = analyzingOrNull.runningNow!!.copy(
                        step = AnalysisProcessStatus.Analyzing.RunningNow.Step.SendingSample
                    )
                )
            }
            vmInteraction.sendFile(samplePath.inputStream().buffered(), sampleExecutionPath)
                .ctx("Sending file")
                .ctx("Analyzing sample ${entity.name}")
                .onLeftLog()
                .getOrElse { error ->
                    _analysisStatus.update {
                        AnalysisProcessStatus.Failed(
                            what = "send file",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    collectionError = true
                    return@takeWhile false
                }
            Logger.d { "Sent file" }
            _analysisStatus.update {
                val analyzingOrNull = (it as? AnalysisProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = analyzingOrNull.runningNow!!.copy(
                        step = AnalysisProcessStatus.Analyzing.RunningNow.Step.LaunchingSampleProcess
                    )
                )
            }
            vmInteraction.prepareCommand("""schtasks /CREATE /SC ONCE /TN "LaunchSample" /TR "$sampleExecutionPath" /ST 00:00 /RL HIGHEST /F""")
                .run()
                .ctx("Preparing command")
                .ctx("Analyzing sample ${entity.name}")
                .getOrElse { error ->
                    _analysisStatus.update {
                        AnalysisProcessStatus.Failed(
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
                .getOrElse { error ->
                    _analysisStatus.update {
                        AnalysisProcessStatus.Failed(
                            what = "run command",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    collectionError = true
                    return@takeWhile false
                }
            Logger.d { "Ran file" }
            _analysisStatus.update {
                val analyzingOrNull = (it as? AnalysisProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = analyzingOrNull.runningNow!!.copy(
                        step = AnalysisProcessStatus.Analyzing.RunningNow.Step.WaitingChanges(
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
                val analyzingOrNull = (it as? AnalysisProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = analyzingOrNull.runningNow!!.copy(
                        step = AnalysisProcessStatus.Analyzing.RunningNow.Step.SavingResults
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
                val analyzingOrNull = (it as? AnalysisProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = analyzingOrNull.runningNow!!.copy(
                        step = AnalysisProcessStatus.Analyzing.RunningNow.Step.StoppingVM
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
                        AnalysisProcessStatus.Failed(
                            what = "stop VM",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    collectionError = true
                    return@takeWhile false
                }
            Logger.d { "Stopped VM" }
            _analysisStatus.update {
                val analyzingOrNull = (it as? AnalysisProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = null,
                    analysisStats = analyzingOrNull.analysisStats.copy(
                        samplesStatus = analyzingOrNull.analysisStats.samplesStatus + mapOf(
                            entity.id.value to changeReport
                        ),
                        totalSamplesAnalyzed = analyzingOrNull.analysisStats.totalSamplesAnalyzed + 1,
                        totalSamples = getTotalSamples()
                    )
                )
            }
            true
        }.collect()
        if (collectionError) return
        Logger.i { "Analysis finished" }
        _analysisStatus.update {
            val analyzingOrNull = (it as? AnalysisProcessStatus.Analyzing ?: return@update it).analysisStats
            AnalysisProcessStatus.Finished(
                AnalysisProcessStatus.AnalysisStats(
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
        get() = AnalysisProcessStatus.ExtendedChangeReport(
            changeFound = when (this) {
                is ChangeReport.NotChanged -> false
                is ChangeReport.Changed -> true
                is ChangeReport.AccessLost -> markAsChangedOnAccessLost
            },
            changeReport = this
        )

    private suspend fun <L, R> retryingOperation(
        maxRetries: Int = 3,
        delay: Duration = 1.seconds,
        operation: suspend () -> Either<L, R>
    ) = Schedule.spaced<L>(delay)
        .and(Schedule.recurs(maxRetries.toLong()))
        .retryEither { operation() }
}