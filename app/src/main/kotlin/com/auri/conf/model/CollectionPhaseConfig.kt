package com.auri.conf.model

import com.auri.core.collection.Collector
import com.auri.core.collection.InfoProvider


data class CollectionPhaseConfig(
    val collectors: List<Collector>,
    val infoProviders: List<InfoProvider> = emptyList()
)