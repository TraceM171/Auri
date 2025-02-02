package com.auri.conf

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.addFileSource
import java.io.File

@OptIn(ExperimentalHoplite::class)
inline fun <reified T : Any> File.configByPrefix(
    prefix: String?
) = ConfigLoaderBuilder.default()
    .addFileSource(this)
    .withExplicitSealedTypes()
    .build()
    .loadConfigOrThrow<T>(prefix = prefix)