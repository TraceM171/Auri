package com.auri.core.extension

import co.touchlab.kermit.Logger
import java.io.File
import java.net.URLClassLoader

fun ClassLoader.withExtensions(
    extensionsFolder: File,
): URLClassLoader {
    val sourceFiles = when {
        extensionsFolder.isFile -> listOf(extensionsFolder)
        else -> extensionsFolder.walkTopDown().filter { it.isFile }.toList()
    }.filter {
        it.name.endsWith(".jar")
    }.map {
        it.toURI().toURL()
    }.toTypedArray()
    Logger.i("Found ${sourceFiles.size} extension(s)")
    return URLClassLoader(sourceFiles, this)
}