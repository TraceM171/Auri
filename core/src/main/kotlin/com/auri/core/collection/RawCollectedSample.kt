package com.auri.core.collection

import kotlinx.datetime.LocalDate
import java.nio.file.Path

data class RawCollectedSample(
    val submissionDate: LocalDate?,
    val name: String,
    val executable: Path,
)
