package com.auri.core.common.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.inputStream

enum class HashAlgorithms(val id: String) {
    MD5("MD5"),
    SHA1("SHA-1"),
    SHA256("SHA-256")
}

@OptIn(ExperimentalStdlibApi::class)
suspend fun Path.hashes(
    vararg algorithms: HashAlgorithms = arrayOf(HashAlgorithms.MD5, HashAlgorithms.SHA1, HashAlgorithms.SHA256)
): Map<HashAlgorithms, String> = withContext(Dispatchers.IO) {
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