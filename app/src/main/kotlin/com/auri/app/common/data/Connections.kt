package com.auri.app.common.data

import org.jetbrains.exposed.sql.Database
import java.io.File

internal fun sqliteConnection(
    file: File
) = Database.connect(
    url = "jdbc:sqlite:${file.absolutePath}",
    driver = "org.sqlite.JDBC"
)