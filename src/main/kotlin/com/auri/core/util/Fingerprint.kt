package com.auri.core.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

enum class Algorithm(val id: String) {
    MD5("MD5"),
    SHA1("SHA-1"),
    SHA256("SHA-256")
}

@OptIn(ExperimentalStdlibApi::class)
suspend fun File.hashes(
    vararg algorithms: Algorithm = arrayOf(Algorithm.MD5, Algorithm.SHA1, Algorithm.SHA256)
): Map<Algorithm, String> = withContext(Dispatchers.IO) {
    val digestions = algorithms.associateWith { MessageDigest.getInstance(it.id) }
    inputStream().use { stream ->
        val buffer = ByteArray(1024)
        var len: Int
        while (stream.read(buffer).also { len = it } != -1) {
            digestions.values.forEach { it.update(buffer, 0, len) }
        }
    }
    digestions.mapValues { it.value.digest().toHexString() }
}