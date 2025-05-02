package com.auri.app.evaluation

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
import com.auri.app.common.data.entity.SampleEvaluationEntity
import com.auri.app.common.data.entity.SampleLivenessCheckTable
import com.auri.app.common.data.getAsFlow
import com.auri.core.analysis.Analyzer
import com.auri.core.analysis.ChangeReport
import com.auri.core.common.util.chainIf
import com.auri.core.common.util.ctx
import com.auri.core.common.util.messageWithCtx
import com.auri.core.common.util.onLeftLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal class EvaluationService(
    private val cacheDir: Path,
    private val samplesDir: Path,
    private val auriDB: Database,
    private val sampleExecutionPath: Path,
    private val vendorVMs: List<VendorVM>,
    private val analyzers: List<Analyzer>,
    private val markAsInmuneOnAccessLost: Boolean,
    private val markAsInmuneAfter: Duration,
    private val analyzeEvery: Duration,
    private val keepListening: KeepListening?
) {
    private val _analysisStatus: MutableStateFlow<EvaluationProcessStatus> =
        MutableStateFlow(EvaluationProcessStatus.NotStarted)
    val analysisStatus = _analysisStatus.asStateFlow()

    suspend fun run() {
        _analysisStatus.update { EvaluationProcessStatus.Initializing }
        val missingDependencies = analyzers.parMapNotNull { analyzer ->
            analyzer.checkDependencies().toNonEmptyListOrNull()?.let { analyzer to it }
        }.associate { it }
        if (missingDependencies.isNotEmpty()) {
            _analysisStatus.update { EvaluationProcessStatus.MissingDependencies(missingDependencies) }
            return
        }
        val samplesFilterQuery = exists(
            SampleLivenessCheckTable.select(RawSampleTable.id)
                .where { SampleLivenessCheckTable.sampleId eq RawSampleTable.id }
                .andWhere { SampleLivenessCheckTable.isAlive eq true }
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

        vendorVMs.forEach { vendorVM ->
            Logger.d { "Capturing initial state for vendor ${vendorVM.info.name}" }
            _analysisStatus.update {
                EvaluationProcessStatus.CapturingGoodState(
                    vendor = vendorVM.info,
                    step = EvaluationProcessStatus.CapturingGoodState.Step.StartingVM
                )
            }
            retryingOperation {
                vendorVM.vmManager.launchVM()
                    .ctx("Launching VM")
                    .ctx("Capturing initial state")
                    .ctx("Vendor ${vendorVM.info.name}")
                    .onLeftLog(severity = Severity.Warn, onlyMessage = true)
            }.onLeftLog()
                .getOrElse { error ->
                    _analysisStatus.update {
                        EvaluationProcessStatus.Failed(
                            what = "launch VM for vendor ${vendorVM.info.name}",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    return
                }
            vendorVM.vmInteraction.awaitReady()
                .ctx("Waiting for VM to be ready")
                .ctx("Capturing initial state")
                .ctx("Vendor ${vendorVM.info.name}")
                .onLeftLog()
                .getOrElse { error ->
                    _analysisStatus.update {
                        EvaluationProcessStatus.Failed(
                            what = "wait for VM for vendor ${vendorVM.info.name}",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    return
                }
            Logger.d { "VM is ready" }
            analyzers.forEach { analyzer ->
                _analysisStatus.update {
                    EvaluationProcessStatus.CapturingGoodState(
                        vendor = vendorVM.info,
                        step = EvaluationProcessStatus.CapturingGoodState.Step.Capturing(
                            analyzer
                        )
                    )
                }
                Logger.d { "Capturing initial state by analyzer ${analyzer.name}" }
                analyzer.captureInitialState(cacheDir, vendorVM.vmInteraction)
                    .ctx("Capturing initial state by analyzer ${analyzer.name}")
                    .ctx("Capturing initial state")
                    .ctx("Vendor ${vendorVM.info.name}")
                    .onLeftLog()
                    .getOrElse { error ->
                        _analysisStatus.update {
                            EvaluationProcessStatus.Failed(
                                what = "capture initial state by analyzer ${analyzer.name} for vendor ${vendorVM.info.name}",
                                why = error.messageWithCtx ?: "Unknown"
                            )
                        }
                        return
                    }
            }
            Logger.d { "Captured initial state" }
            _analysisStatus.update {
                EvaluationProcessStatus.CapturingGoodState(
                    vendor = vendorVM.info,
                    step = EvaluationProcessStatus.CapturingGoodState.Step.StoppingVM
                )
            }
            Logger.d { "Stopping VM" }
            retryingOperation {
                vendorVM.vmManager.stopVM()
                    .ctx("Stopping VM")
                    .ctx("Capturing initial state")
                    .ctx("Vendor ${vendorVM.info.name}")
                    .onLeftLog(severity = Severity.Warn, onlyMessage = true)
            }.onLeftLog()
                .getOrElse { error ->
                    _analysisStatus.update {
                        EvaluationProcessStatus.Failed(
                            what = "stop VM for vendor ${vendorVM.info.name}",
                            why = error.messageWithCtx ?: "Unknown"
                        )
                    }
                    return
                }
            Logger.d { "Stopped VM" }
            Logger.d { "Initial state captured for vendor ${vendorVM.info.name}" }
        }

        _analysisStatus.update {
            EvaluationProcessStatus.Analyzing(
                runningNow = null,
                EvaluationProcessStatus.EvaluationStats(
                    vendorStats = vendorVMs.associate { vendorVM ->
                        vendorVM.info to EvaluationProcessStatus.VendorStats(
                            detectedSamples = 0,
                            analyzedSamples = 0
                        )
                    }.toMap(),
                    totalSamplesAnalyzed = 0,
                    totalSamples = getTotalSamples(),
                )
            )
        }
        var collectionError = false
        allSamples.takeWhile { entity ->
            Logger.d { "Starting analysis for sample ${entity.name}" }
            vendorVMs.forEach { vendorVM ->
                Logger.d { "Starting analysis for vendor ${vendorVM.info.name}" }
                val alreadyAnalyzed = newSuspendedTransaction(
                    context = Dispatchers.IO,
                    db = auriDB
                ) {
                    SampleEvaluationEntity.findById(
                        SampleEvaluationEntity.id(entity.id.value, vendorVM.info.name)
                    ) != null
                }
                if (alreadyAnalyzed) {
                    Logger.d { "Sample ${entity.name} already analyzed by vendor ${vendorVM.info.name}, skipping." }
                    return@forEach
                }
                val samplePath = samplesDir.resolve(entity.path)
                _analysisStatus.update {
                    val analyzingOrNull = (it as? EvaluationProcessStatus.Analyzing ?: return@update it)
                    analyzingOrNull.copy(
                        runningNow = EvaluationProcessStatus.Analyzing.RunningNow(
                            vendor = vendorVM.info,
                            sampleId = entity.id.value,
                            step = EvaluationProcessStatus.Analyzing.RunningNow.Step.StartingVM
                        )
                    )
                }
                retryingOperation {
                    vendorVM.vmManager.launchVM()
                        .ctx("Launching VM")
                        .ctx("Vendor ${vendorVM.info.name}")
                        .ctx("Analyzing sample ${entity.name}")
                        .onLeftLog(severity = Severity.Warn, onlyMessage = true)
                }.onLeftLog()
                    .getOrElse { error ->
                        _analysisStatus.update {
                            EvaluationProcessStatus.Failed(
                                what = "launch VM",
                                why = error.messageWithCtx ?: "Unknown"
                            )
                        }
                        collectionError = true
                        return@takeWhile false
                    }
                Logger.d { "Launched VM" }
                Logger.d { "Waiting for VM to be ready" }
                vendorVM.vmInteraction.awaitReady()
                    .ctx("Waiting for VM to be ready")
                    .ctx("Vendor ${vendorVM.info.name}")
                    .ctx("Analyzing sample ${entity.name}")
                    .onLeftLog()
                    .getOrElse { error ->
                        _analysisStatus.update {
                            EvaluationProcessStatus.Failed(
                                what = "wait for VM",
                                why = error.messageWithCtx ?: "Unknown"
                            )
                        }
                        collectionError = true
                        return@takeWhile false
                    }
                Logger.d { "VM is ready" }
                _analysisStatus.update {
                    val analyzingOrNull = (it as? EvaluationProcessStatus.Analyzing ?: return@update it)
                    analyzingOrNull.copy(
                        runningNow = analyzingOrNull.runningNow!!.copy(
                            step = EvaluationProcessStatus.Analyzing.RunningNow.Step.SendingSample
                        )
                    )
                }
                retryingOperation {
                    vendorVM.vmInteraction.sendFile(samplePath.inputStream().buffered(), sampleExecutionPath)
                }.ctx("Sending file")
                    .ctx("Vendor ${vendorVM.info.name}")
                    .ctx("Analyzing sample ${entity.name}")
                    .onLeftLog()
                    .getOrElse { error ->
                        _analysisStatus.update {
                            EvaluationProcessStatus.Failed(
                                what = "send file",
                                why = error.messageWithCtx ?: "Unknown"
                            )
                        }
                        collectionError = true
                        return@takeWhile false
                    }
                Logger.d { "Sent file" }
                _analysisStatus.update {
                    val analyzingOrNull = (it as? EvaluationProcessStatus.Analyzing ?: return@update it)
                    analyzingOrNull.copy(
                        runningNow = analyzingOrNull.runningNow!!.copy(
                            step = EvaluationProcessStatus.Analyzing.RunningNow.Step.LaunchingSampleProcess
                        )
                    )
                }
                val launchSampleScriptPath = sampleExecutionPath.resolveSibling("launch.bat")
                val launchSampleScriptContents = """
                @echo off
                cd /d "${sampleExecutionPath.parent}"
                "${sampleExecutionPath.name}"
            """.trimIndent()
                retryingOperation {
                    vendorVM.vmInteraction.sendFile(
                        launchSampleScriptContents.byteInputStream().buffered(),
                        launchSampleScriptPath
                    )
                }.ctx("Sending launch script file")
                    .ctx("Vendor ${vendorVM.info.name}")
                    .ctx("Analyzing sample ${entity.name}")
                    .onLeftLog()
                    .getOrElse { error ->
                        _analysisStatus.update {
                            EvaluationProcessStatus.Failed(
                                what = "send launch script file",
                                why = error.messageWithCtx ?: "Unknown"
                            )
                        }
                        collectionError = true
                        return@takeWhile false
                    }
                retryingOperation {
                    vendorVM.vmInteraction.prepareCommand("""schtasks /CREATE /SC ONCE /TN "LaunchSample" /TR "$launchSampleScriptPath" /ST 00:00 /RL HIGHEST /F""")
                        .run()
                }.ctx("Preparing command")
                    .ctx("Vendor ${vendorVM.info.name}")
                    .ctx("Analyzing sample ${entity.name}")
                    .onLeftLog()
                    .getOrElse { error ->
                        _analysisStatus.update {
                            EvaluationProcessStatus.Failed(
                                what = "prepare command",
                                why = error.messageWithCtx ?: "Unknown"
                            )
                        }
                        collectionError = true
                        return@takeWhile false
                    }
                retryingOperation {
                    vendorVM.vmInteraction.prepareCommand("""schtasks /RUN /TN "LaunchSample"""")
                        .run()
                }.ctx("Running command")
                    .ctx("Vendor ${vendorVM.info.name}")
                    .ctx("Analyzing sample ${entity.name}")
                    .onLeftLog()
                    .getOrElse { error ->
                        _analysisStatus.update {
                            EvaluationProcessStatus.Failed(
                                what = "run command",
                                why = error.messageWithCtx ?: "Unknown"
                            )
                        }
                        collectionError = true
                        return@takeWhile false
                    }
                Logger.d { "Ran file" }
                _analysisStatus.update {
                    val analyzingOrNull = (it as? EvaluationProcessStatus.Analyzing ?: return@update it)
                    analyzingOrNull.copy(
                        runningNow = analyzingOrNull.runningNow!!.copy(
                            step = EvaluationProcessStatus.Analyzing.RunningNow.Step.WaitingChanges(
                                sampleTimeout = Clock.System.now() + markAsInmuneAfter
                            )
                        )
                    )
                }
                val detectionStart = TimeSource.Monotonic.markNow()
                val changeReport = withTimeoutOrNull(markAsInmuneAfter) {
                    var lostAccessCount = 0
                    Schedule.spaced<Unit>(analyzeEvery).retryEither {
                        analyzers.forEach { analyzer ->
                            Logger.d { "Analyzing with ${analyzer.name}" }
                            val report = analyzer.reportChanges(cacheDir, vendorVM.vmInteraction)
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
                } ?: ChangeReport.NotChanged
                val detectionTime = detectionStart.elapsedNow()
                Logger.d { "Analysis for sample ${entity.name} finished, changes detected: $changeReport" }
                _analysisStatus.update {
                    val analyzingOrNull = (it as? EvaluationProcessStatus.Analyzing ?: return@update it)
                    analyzingOrNull.copy(
                        runningNow = analyzingOrNull.runningNow!!.copy(
                            step = EvaluationProcessStatus.Analyzing.RunningNow.Step.SavingResults
                        )
                    )
                }
                newSuspendedTransaction(context = Dispatchers.IO, db = auriDB) {
                    SampleEvaluationEntity.new(
                        SampleEvaluationEntity.id(entity.id.value, vendorVM.info.name)
                    ) {
                        this.checkDate = LocalDate.now().toKotlinLocalDate()
                        this.timeToDetect = detectionTime
                        this.isInmune = changeReport.isInmune
                        this.isInmuneReason = changeReport.json
                    }
                }
                _analysisStatus.update {
                    val analyzingOrNull = (it as? EvaluationProcessStatus.Analyzing ?: return@update it)
                    analyzingOrNull.copy(
                        runningNow = analyzingOrNull.runningNow!!.copy(
                            step = EvaluationProcessStatus.Analyzing.RunningNow.Step.StoppingVM
                        )
                    )
                }
                retryingOperation {
                    vendorVM.vmManager.stopVM()
                        .ctx("Stopping VM")
                        .ctx("Vendor ${vendorVM.info.name}")
                        .ctx("Analyzing sample ${entity.name}")
                        .onLeftLog(severity = Severity.Warn, onlyMessage = true)
                }.onLeftLog()
                    .getOrElse { error ->
                        _analysisStatus.update {
                            EvaluationProcessStatus.Failed(
                                what = "stop VM",
                                why = error.messageWithCtx ?: "Unknown"
                            )
                        }
                        collectionError = true
                        return@takeWhile false
                    }
                Logger.d { "Stopped VM" }
                _analysisStatus.update {
                    val analyzingOrNull = (it as? EvaluationProcessStatus.Analyzing ?: return@update it)
                    val currentVendorStats = analyzingOrNull.evaluationStats.vendorStats.getOrDefault(
                        vendorVM.info,
                        EvaluationProcessStatus.VendorStats(
                            detectedSamples = 0,
                            analyzedSamples = 0
                        )
                    )
                    val newVendorStats = currentVendorStats.copy(
                        detectedSamples = currentVendorStats.detectedSamples
                            .chainIf(!changeReport.isInmune, Int::inc),
                        analyzedSamples = currentVendorStats.analyzedSamples + 1
                    )
                    analyzingOrNull.copy(
                        runningNow = null,
                        evaluationStats = analyzingOrNull.evaluationStats.copy(
                            vendorStats = analyzingOrNull.evaluationStats.vendorStats + mapOf(
                                vendorVM.info to newVendorStats
                            )
                        )
                    )
                }
            }
            _analysisStatus.update {
                val analyzingOrNull = (it as? EvaluationProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = null,
                    evaluationStats = analyzingOrNull.evaluationStats.copy(
                        totalSamplesAnalyzed = analyzingOrNull.evaluationStats.totalSamplesAnalyzed + 1,
                        totalSamples = getTotalSamples(),
                    )
                )
            }
            true
        }.collect()
        if (collectionError) return
        Logger.i { "Analysis finished" }
        _analysisStatus.update {
            val analyzingOrNull = (it as? EvaluationProcessStatus.Analyzing ?: return@update it).evaluationStats
            EvaluationProcessStatus.Finished(
                EvaluationProcessStatus.EvaluationStats(
                    vendorStats = analyzingOrNull.vendorStats,
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

    private val ChangeReport.isInmune
        get() = when (this) {
            is ChangeReport.NotChanged -> false
            is ChangeReport.Changed -> true
            is ChangeReport.AccessLost -> markAsInmuneOnAccessLost
        }

    private suspend fun <L, R> retryingOperation(
        maxRetries: Int = 5,
        delay: Duration = 3.seconds,
        operation: suspend () -> Either<L, R>
    ) = Schedule.spaced<L>(delay)
        .and(Schedule.recurs(maxRetries.toLong()))
        .retryEither { operation() }

}

