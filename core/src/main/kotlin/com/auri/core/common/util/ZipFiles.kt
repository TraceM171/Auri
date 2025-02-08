package com.auri.core.common.util

import net.lingala.zip4j.ZipFile
import java.io.File

fun File.unzip(
    destinationDirectory: File,
    password: String? = null,
) = ZipFile(this, password?.toCharArray())
    .extractAll(destinationDirectory.absolutePath)