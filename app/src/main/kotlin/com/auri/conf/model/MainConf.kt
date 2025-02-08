package com.auri.conf.model

import java.io.File

data class MainConf(
    val version: String,
    val extensionsFolder: File? = null
)
