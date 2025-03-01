package com.auri.conf

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.addFileSource
import java.io.File

@OptIn(ExperimentalHoplite::class)
inline fun <reified T : Any> File.configByPrefix(
    classLoader: ClassLoader = T::class.java.classLoader,
    prefix: String? = null
): Either<Throwable, T> = either {
    catch(
        {
            ConfigLoaderBuilder.default()
                .addFileSource(this@configByPrefix)
                .addDecoder(SubclassDecoder(classLoader))
                .withExplicitSealedTypes()
                .build()
                .loadConfigOrThrow<T>(prefix = prefix)
        },
        { raise(it) }
    )
}