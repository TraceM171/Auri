package com.auri.app.liveness

import arrow.core.Nel
import com.auri.core.analysis.Analyzer
import com.auri.core.analysis.ChangeReport
import com.auri.core.common.MissingDependency
import kotlinx.datetime.Instant

sealed interface LivenessProcessStatus {
    data object NotStarted : LivenessProcessStatus
    data object Initializing : LivenessProcessStatus
    data class Failed(val what: String, val why: String) : LivenessProcessStatus
    data class MissingDependencies(
        val missingDependencies: Map<Analyzer, Nel<MissingDependency>>
    ) : LivenessProcessStatus

    data class CapturingGoodState(
        val step: Step,
    ) : LivenessProcessStatus {
        sealed interface Step {
            data object StartingVM : Step
            data class Capturing(val analyzer: Analyzer) : Step
            data object StoppingVM : Step
        }
    }

    data class Analyzing(
        val runningNow: RunningNow?,
        val livenessStats: LivenessStats
    ) : LivenessProcessStatus {
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
        val livenessStats: LivenessStats
    ) : LivenessProcessStatus


    data class LivenessStats(
        val samplesStatus: Map<Int, ExtendedChangeReport>,
        val totalSamplesAnalyzed: Int,
        val totalSamples: Int
    )

    data class ExtendedChangeReport(
        val changeFound: Boolean,
        val changeReport: ChangeReport
    )
}