package com.auri.app.common

import co.touchlab.kermit.*
import co.touchlab.kermit.io.RollingFileLogWriter
import org.slf4j.ILoggerFactory
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import kotlin.streams.asSequence

internal class Slf4jKermitLogger : AbstractLogger() {
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
                marker?.toString().orEmpty(),
                throwable,
                formatted ?: (messagePattern ?: "")
            )
        }
    }
}

internal class KermitServiceProvider : SLF4JServiceProvider {
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

class DefaultMessageFormatter(
    private val basePackageName: String
) : MessageStringFormatter {
    private val excludedClasses = listOf(
        this::class.qualifiedName,
        Slf4jKermitLogger::class.qualifiedName,
        RollingFileLogWriter::class.qualifiedName
    )

    override fun formatMessage(
        severity: Severity?,
        tag: Tag?,
        message: Message
    ): String {
        val callingClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { frames ->
            var firstClass: StackWalker.StackFrame? = null
            frames.asSequence()
                .filter { it.className !in excludedClasses }
                .onEach { if (firstClass == null) firstClass = it }
                .firstOrNull { it.className.startsWith(basePackageName) }
                ?: firstClass
        }?.className
            ?.substringAfterLast('.')
            ?.substringBefore('$')
            ?: "UKNOWN"

        return "[$callingClass] $severity: ${tag?.let { "${it.tag} " }} ${message.message}"
    }
}