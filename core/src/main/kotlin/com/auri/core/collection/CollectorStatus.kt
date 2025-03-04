package com.auri.core.collection

import kotlinx.datetime.Instant

sealed interface CollectorStatus {
    data object Done : CollectorStatus
    data class DoneUntilNextPeriod(val nextPeriodStart: Instant) : CollectorStatus
    data class Downloading(val what: String) : CollectorStatus
    data class Retrying(val what: String, val why: String, val nextTryStart: Instant) : CollectorStatus
    data class Failed(val what: String, val why: String) : CollectorStatus
    data class Processing(val what: String) : CollectorStatus
    data class NewSample(val sample: RawCollectedSample) : CollectorStatus
}