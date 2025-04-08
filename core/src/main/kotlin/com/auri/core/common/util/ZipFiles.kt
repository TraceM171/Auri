package com.auri.core.common.util

import net.lingala.zip4j.ZipFile
import java.nio.file.Path
import kotlin.io.path.absolutePathString

fun Path.unzip(
    destinationDirectory: Path,
    password: String? = null,
) = ZipFile(this.toFile(), password?.toCharArray())
    .extractAll(destinationDirectory.absolutePathString())