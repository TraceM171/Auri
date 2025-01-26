package com.auri.core

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import org.slf4j.ILoggerFactory
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.NOPMDCAdapter

class Slf4jKermitLogger : AbstractLogger() {
    private val logger = Logger
    override fun getName(): String = "slf4j-over-kermit"

    //region Is Logging enabled at various levels
    override fun isTraceEnabled() = logger.config.minSeverity <= Severity.Verbose
    override fun isTraceEnabled(marker: Marker?) = logger.config.minSeverity <= Severity.Verbose
    override fun isDebugEnabled() = logger.config.minSeverity <= Severity.Debug
    override fun isDebugEnabled(marker: Marker?) = logger.config.minSeverity <= Severity.Debug
    override fun isInfoEnabled() = logger.config.minSeverity <= Severity.Info
    override fun isInfoEnabled(marker: Marker?) = logger.config.minSeverity <= Severity.Info
    override fun isWarnEnabled() = logger.config.minSeverity <= Severity.Warn
    override fun isWarnEnabled(marker: Marker?) = logger.config.minSeverity <= Severity.Warn
    override fun isErrorEnabled() = logger.config.minSeverity <= Severity.Error
    override fun isErrorEnabled(marker: Marker?) = logger.config.minSeverity <= Severity.Error
    //endregion

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level?,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any>?,
        throwable: Throwable?
    ) {
        val severity = when (level) {
            Level.ERROR -> Severity.Error
            Level.WARN -> Severity.Warn
            Level.INFO -> Severity.Info
            Level.DEBUG -> Severity.Debug
            else -> Severity.Verbose
        }

        val formatted = if (messagePattern != null && arguments != null) {
            String.format(messagePattern, *(arguments.toList().toTypedArray()))
        } else null

        messagePattern.let {
            logger.log(
                severity,
                marker?.toString() ?: getName(),
                throwable,
                formatted ?: (messagePattern ?: "")
            )
        }
    }
}

class KermitServiceProvider : org.slf4j.spi.SLF4JServiceProvider {
    private val markerFactory = BasicMarkerFactory()
    private val mdcAdapter = NOPMDCAdapter()
    override fun getLoggerFactory() = ILoggerFactory {
        Slf4jKermitLogger()
    }

    override fun getMarkerFactory() = markerFactory

    override fun getMDCAdapter() = mdcAdapter

    override fun getRequestedApiVersion() = "2.0.99"

    override fun initialize() = Unit
}