package com.auri.core.collection

sealed interface CollectorStatus {
    data object Done : CollectorStatus
    data object DoneUntilNextPeriod : CollectorStatus
    data class Downloading(val what: String) : CollectorStatus
    data class Retrying(val what: String, val why: String) : CollectorStatus
    data class Failed(val what: String, val why: String) : CollectorStatus
    data class Processing(val what: String) : CollectorStatus
    data class NewSample(val sample: RawCollectedSample) : CollectorStatus
}