/*
package com.auri.app

import com.auri.conf.model.MainConf
import com.auri.conf.configByPrefix
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.addFileSource
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File

fun setupKoin(
    configFile: File
) = koinApplication {
    modules(
        configModule(configFile)
    )
}

private fun configModule(
    configFile: File
) = module {
    single<MainConf> {
        configByPrefix(
            configFile = configFile,
            prefix = null
        )
    }
}*/
