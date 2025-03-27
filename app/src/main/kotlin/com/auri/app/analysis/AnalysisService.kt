package com.auri.app.analysis

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.toNonEmptyListOrNull
import arrow.fx.coroutines.parMapNotNull
import arrow.resilience.Schedule
import arrow.resilience.retryEither
import co.touchlab.kermit.Logger
import com.auri.app.common.data.KeepListening
import com.auri.app.common.data.entity.RawSampleEntity
import com.auri.app.common.data.entity.RawSampleTable
import com.auri.app.common.data.entity.SampleLivenessCheckEntity
import com.auri.app.common.data.entity.SampleLivenessCheckTable
import com.auri.app.common.data.getAsFlow
import com.auri.core.analysis.Analyzer
import com.auri.core.analysis.VMInteraction
import com.auri.core.analysis.VMManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.notExists
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.io.File
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.inputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class AnalysisService(
    private val cacheDir: File,
    private val samplesDir: Path,
    private val auriDB: Database,
    private val sampleExecutionPath: Path,
    private val vmManager: VMManager,
    private val vmInteraction: VMInteraction,
    private val analyzers: List<Analyzer>,
    private val markAsInactiveAfter: Duration = 5.minutes,
    private val analyzeEvery: Duration = 15.seconds,
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
            keepListening = KeepListening(
                pollTime = 1.seconds
            )
        )

        _analysisStatus.update { AnalysisProcessStatus.CapturingGoodState(AnalysisProcessStatus.CapturingGoodState.Step.StartingVM) }
        vmManager.launchVM().getOrElse {
            _analysisStatus.update { AnalysisProcessStatus.Failed(what = "Failed to launch VM", why = "Unknown") }
            return
        }
        vmInteraction.awaitReady().getOrElse {
            _analysisStatus.update {
                AnalysisProcessStatus.Failed(
                    what = "Failed to wait for VM",
                    why = "Unknown"
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
            analyzer.captureInitialState(cacheDir, vmInteraction).getOrElse {
                _analysisStatus.update {
                    AnalysisProcessStatus.Failed(
                        what = "Failed to capture initial state by analyzer ${analyzer.name}",
                        why = "Unknown"
                    )
                }
                return
            }
        }
        Logger.d { "Captured initial state" }
        _analysisStatus.update { AnalysisProcessStatus.CapturingGoodState(AnalysisProcessStatus.CapturingGoodState.Step.StoppingVM) }
        Logger.d { "Stopping VM" }
        vmManager.stopVM().getOrElse {
            _analysisStatus.update { AnalysisProcessStatus.Failed(what = "Failed to stop VM", why = "Unknown") }
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
        allSamples.collect { entity -> // TODO: remove limit, used for testing
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
            vmManager.launchVM().getOrElse {
                _analysisStatus.update {
                    AnalysisProcessStatus.Failed(
                        what = "Failed to launch VM",
                        why = "Unknown"
                    )
                }
                collectionError = true
                return@collect
            }
            Logger.d { "Launched VM" }
            Logger.d { "Waiting for VM to be ready" }
            vmInteraction.awaitReady().getOrElse {
                _analysisStatus.update {
                    AnalysisProcessStatus.Failed(
                        what = "Failed to wait for VM",
                        why = "Unknown"
                    )
                }
                collectionError = true
                return@collect
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
            vmInteraction.sendFile(samplePath.inputStream().buffered(), sampleExecutionPath).getOrElse {
                _analysisStatus.update {
                    AnalysisProcessStatus.Failed(
                        what = "Failed to send file",
                        why = "Unknown"
                    )
                }
                collectionError = true
                return@collect
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
                .run().getOrElse {
                    _analysisStatus.update {
                        AnalysisProcessStatus.Failed(
                            what = "Failed to prepare command",
                            why = "Unknown"
                        )
                    }
                    collectionError = true
                    return@collect
                }
            vmInteraction.prepareCommand("""schtasks /RUN /TN "LaunchSample"""")
                .run().getOrElse {
                    _analysisStatus.update {
                        AnalysisProcessStatus.Failed(
                            what = "Failed to run command",
                            why = "Unknown"
                        )
                    }
                    collectionError = true
                    return@collect
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
            val changed = withTimeoutOrNull(markAsInactiveAfter) {
                Schedule.spaced<Unit>(analyzeEvery).retryEither {
                    analyzers.forEach { analyzer ->
                        Logger.d { "Analyzing with ${analyzer.name}" }
                        analyzer.reportChanges(cacheDir, vmInteraction).getOrNull().also {
                            Logger.d { "Analyzer ${analyzer.name} finished: $it" }
                        }?.takeIf { it.changeFound }?.let { return@withTimeoutOrNull true }
                    }
                    Unit.left()
                }
                false
            } == true
            Logger.d { "Analysis for sample ${entity.name} finished, changes detected: $changed" }
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
                    this.isAlive = changed
                    this.isAliveReason = "" // TODO: add reason
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
            vmManager.stopVM().getOrElse {
                _analysisStatus.update {
                    AnalysisProcessStatus.Failed(
                        what = "Failed to stop VM",
                        why = "Unknown"
                    )
                }
                collectionError = true
                return@collect
            }
            Logger.d { "Stopped VM" }
            _analysisStatus.update {
                val analyzingOrNull = (it as? AnalysisProcessStatus.Analyzing ?: return@update it)
                analyzingOrNull.copy(
                    runningNow = null,
                    analysisStats = analyzingOrNull.analysisStats.copy(
                        samplesStatus = analyzingOrNull.analysisStats.samplesStatus + mapOf(
                            entity.id.value to changed
                        ),
                        totalSamplesAnalyzed = analyzingOrNull.analysisStats.totalSamplesAnalyzed + 1,
                        totalSamples = getTotalSamples()
                    )
                )
            }
        }
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
}