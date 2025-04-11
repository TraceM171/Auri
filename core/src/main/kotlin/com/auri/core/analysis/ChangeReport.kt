package com.auri.core.analysis

import arrow.core.Nel

sealed interface ChangeReport {
    data object NotChanged : ChangeReport
    data object AccessLost : ChangeReport
    data class Changed(val what: Nel<String>) : ChangeReport
}