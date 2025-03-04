package com.auri.app.conf.model

import com.auri.core.collection.Collector
import com.auri.core.collection.InfoProvider


internal data class CollectionPhaseConfig(
    val collectors: List<Collector>,
    val infoProviders: List<InfoProvider> = emptyList()
)