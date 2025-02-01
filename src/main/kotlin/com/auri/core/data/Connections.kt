package com.auri.core.data

import org.jetbrains.exposed.sql.Database
import java.io.File

fun sqliteConnection(
    file: File
) = Database.connect(
    url = "jdbc:sqlite:${file.absolutePath}",
    driver = "org.sqlite.JDBC"
)