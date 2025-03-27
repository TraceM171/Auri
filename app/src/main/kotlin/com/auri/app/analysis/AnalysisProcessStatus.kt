package com.auri.app.analysis

import arrow.core.Nel
import com.auri.core.analysis.Analyzer
import com.auri.core.common.MissingDependency
import kotlinx.datetime.Instant

sealed interface AnalysisProcessStatus {
    data object NotStarted : AnalysisProcessStatus
    data object Initializing : AnalysisProcessStatus
    data class Failed(val what: String, val why: String) : AnalysisProcessStatus
    data class MissingDependencies(
        val missingDependencies: Map<Analyzer, Nel<MissingDependency>>
    ) : AnalysisProcessStatus

    data class CapturingGoodState(
        val step: Step,
    ) : AnalysisProcessStatus {
        sealed interface Step {
            data object StartingVM : Step
            data class Capturing(val analyzer: Analyzer) : Step
            data object StoppingVM : Step
        }
    }

    data class Analyzing(
        val runningNow: RunningNow?,
        val analysisStats: AnalysisStats
    ) : AnalysisProcessStatus {
        data class RunningNow(
            val sampleId: Int,
            val step: Step,
        ) {
            sealed class Step {
                data object StartingVM : Step()
                data object SendingSample : Step()
                data object LaunchingSampleProcess : Step()
                data class WaitingChanges(val sampleTimeout: Instant) : Step()
                data object SavingResults : Step()
                data object StoppingVM : Step()
            }
        }
    }

    data class Finished(
        val analysisStats: AnalysisStats
    ) : AnalysisProcessStatus


    data class AnalysisStats(
        val samplesStatus: Map<Int, Boolean>,
        val totalSamplesAnalyzed: Int,
        val totalSamples: Int
    )
}