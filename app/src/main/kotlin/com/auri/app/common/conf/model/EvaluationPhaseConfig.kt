package com.auri.app.common.conf.model

import com.auri.app.common.data.KeepListening
import com.auri.app.evaluation.VendorVM
import com.auri.core.analysis.Analyzer
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


internal data class EvaluationPhaseConfig(
    val sampleExecutionPath: Path,
    val vendorVMs: List<VendorVM>,
    val analyzers: List<Analyzer>,
    val markAsInmuneOnAccessLost: Boolean = true,
    val markAsInmuneAfter: Duration = 5.minutes,
    val analyzeEvery: Duration = 15.seconds,
    val keepListening: KeepListening = KeepListening(pollTime = 1.seconds),
)