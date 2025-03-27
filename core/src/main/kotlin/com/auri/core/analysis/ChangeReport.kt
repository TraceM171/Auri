package com.auri.core.analysis

sealed class ChangeReport(val changeFound: Boolean) {
    data object NotChanged : ChangeReport(false)
    data object AccessLost : ChangeReport(true)
    data object Changed : ChangeReport(true)
}