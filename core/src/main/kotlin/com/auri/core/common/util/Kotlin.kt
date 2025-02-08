package com.auri.core.common.util

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
inline fun <T> T.chainIf(
    condition: Boolean,
    block: T.() -> T
): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return if (condition) block() else this
}

@OptIn(ExperimentalContracts::class)
inline fun <T, R : Any> T.chainIfNotNull(
    value: R?,
    block: T.(value: R) -> T
): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return value?.let { block(it) } ?: this
}
