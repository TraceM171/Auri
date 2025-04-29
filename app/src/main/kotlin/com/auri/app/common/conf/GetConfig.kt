package com.auri.app.common.conf

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.addFileSource
import java.nio.file.Path

@OptIn(ExperimentalHoplite::class)
internal inline fun <reified T : Any> Path.configByPrefix(
    classLoader: ClassLoader = T::class.java.classLoader,
    prefix: String? = null
): Either<Throwable, T> = either {
    catch(
        {
            ConfigLoaderBuilder.default()
                .addFileSource(this@configByPrefix.toFile())
                .addDecoder(SubclassDecoder(classLoader))
                .withExplicitSealedTypes()
                .build()
                .loadConfigOrThrow<T>(prefix = prefix)
        },
        { raise(it) }
    )
}