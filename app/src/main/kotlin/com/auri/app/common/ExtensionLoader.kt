package com.auri.app.common

import co.touchlab.kermit.Logger
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.walk

internal fun ClassLoader.withExtensions(
    extensionsFolder: Path,
): URLClassLoader {
    val sourceFiles = when {
        extensionsFolder.isRegularFile() -> listOf(extensionsFolder)
        else -> extensionsFolder.walk().filter { it.isRegularFile() }.toList()
    }.filter {
        it.name.endsWith(".jar")
    }.map {
        it.toUri().toURL()
    }.toTypedArray()
    Logger.i("Found ${sourceFiles.size} extension(s)")
    return URLClassLoader(sourceFiles, this)
}