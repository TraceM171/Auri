package com.auri.cli

import com.auri.core.common.util.chainIf

fun String.elipsis(maxLength: Int, elipsisText: String = "…"): String {
    val tipPointLength = (maxLength - elipsisText.length).coerceAtLeast(0)
    return chainIf(length > tipPointLength) {
        buildString {
            append(this@elipsis.take(tipPointLength))
            append(elipsisText)
        }
    }
}