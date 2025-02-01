package com.auri.core.util

import java.io.InputStream

fun getResource(fileName: String): InputStream? =
    object {}.javaClass.getResourceAsStream("/$fileName")