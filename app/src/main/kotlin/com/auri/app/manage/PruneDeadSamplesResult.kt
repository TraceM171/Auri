package com.auri.app.manage

data class PruneDeadSamplesResult(
    val prunedSamples: Int,
    val bytesFreed: Long,
)