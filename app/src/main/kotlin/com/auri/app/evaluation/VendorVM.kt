package com.auri.app.evaluation

import com.auri.core.analysis.VMInteraction
import com.auri.core.analysis.VMManager

data class VendorVM(
    val info: VendorInfo,
    val vmManager: VMManager,
    val vmInteraction: VMInteraction
)