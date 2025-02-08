package com.auri.core.common.util

import java.io.File

enum class MagicNumber(val value: String) {
    PE("4d5a")
}

fun File.magicNumber(): MagicNumber? {
    if (isDirectory) return null
    val maxMagicNumberLength = MagicNumber.entries.maxByOrNull { it.value.length }?.value?.length
        ?: error("No magic numbers defined")
    val buffer = ByteArray(maxMagicNumberLength)
    inputStream().use { it.read(buffer) }
    val hex = buffer.joinToString("") { "%02x".format(it) }
    return MagicNumber.entries.firstOrNull { hex.startsWith(it.value) }
}