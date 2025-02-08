package com.auri.core.common.util

infix fun String.containsMatch(regex: Regex): Boolean = regex.containsMatchIn(this)

fun Regex.withOptions(options: Set<RegexOption>): Regex {
    return Regex(pattern, options)
}

fun Regex.withMultilineOption(): Regex = withOptions(setOf(RegexOption.MULTILINE))