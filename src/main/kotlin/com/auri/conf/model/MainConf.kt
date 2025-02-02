package com.auri.conf.model

data class MainConf(
    val version: String,
    val extraPackages: List<String> = emptyList()
)
