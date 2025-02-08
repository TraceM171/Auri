package com.auri.conf

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.addFileSource
import java.io.File

@OptIn(ExperimentalHoplite::class)
inline fun <reified T : Any> File.configByPrefix(
    classLoader: ClassLoader = T::class.java.classLoader,
    prefix: String? = null
) = ConfigLoaderBuilder.default()
    .addFileSource(this)
    .addDecoder(SubclassDecoder(classLoader))
    .withExplicitSealedTypes()
    .build()
    .loadConfigOrThrow<T>(prefix = prefix)