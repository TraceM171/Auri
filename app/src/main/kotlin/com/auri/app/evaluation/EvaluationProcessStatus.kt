package com.auri.app.evaluation

import arrow.core.Nel
import com.auri.core.analysis.Analyzer
import com.auri.core.common.MissingDependency
import kotlinx.datetime.Instant

sealed interface EvaluationProcessStatus {
    data object NotStarted : EvaluationProcessStatus
    data object Initializing : EvaluationProcessStatus
    data class Failed(val what: String, val why: String) : EvaluationProcessStatus
    data class MissingDependencies(
        val missingDependencies: Map<Analyzer, Nel<MissingDependency>>
    ) : EvaluationProcessStatus

    data class CapturingGoodState(
        val vendor: VendorInfo,
        val step: Step,
    ) : EvaluationProcessStatus {
        sealed interface Step {
            data object StartingVM : Step
            data class Capturing(val analyzer: Analyzer) : Step
            data object StoppingVM : Step
        }
    }

    data class Analyzing(
        val runningNow: RunningNow?,
        val evaluationStats: EvaluationStats
    ) : EvaluationProcessStatus {
        data class RunningNow(
            val sampleId: Int,
            val vendor: VendorInfo,
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
        val evaluationStats: EvaluationStats
    ) : EvaluationProcessStatus


    data class EvaluationStats(
        val vendorStats: Map<VendorInfo, VendorStats>,
        val totalSamplesAnalyzed: Int,
        val totalSamples: Int,
    )

    data class VendorStats(
        val blockedSamples: Int,
        val analyzedSamples: Int,
    )
}