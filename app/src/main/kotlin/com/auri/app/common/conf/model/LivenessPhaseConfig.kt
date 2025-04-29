package com.auri.app.common.conf.model

import com.auri.app.common.data.KeepListening
import com.auri.core.analysis.Analyzer
import com.auri.core.analysis.VMInteraction
import com.auri.core.analysis.VMManager
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


internal data class LivenessPhaseConfig(
    val sampleExecutionPath: Path,
    val vmManager: VMManager,
    val vmInteraction: VMInteraction,
    val analyzers: List<Analyzer>,
    val markAsChangedOnAccessLost: Boolean = true,
    val markAsInactiveAfter: Duration = 5.minutes,
    val analyzeEvery: Duration = 15.seconds,
    val keepListening: KeepListening?
)