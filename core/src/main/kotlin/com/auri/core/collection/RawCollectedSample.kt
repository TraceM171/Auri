package com.auri.core.collection

import kotlinx.datetime.LocalDate
import java.io.File

data class RawCollectedSample(
    val submissionDate: LocalDate?,
    val name: String,
    val executable: File,
)
