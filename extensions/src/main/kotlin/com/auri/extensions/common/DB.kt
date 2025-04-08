package com.auri.extensions.common

import org.jetbrains.exposed.sql.Database
import java.nio.file.Path
import kotlin.io.path.absolutePathString

fun sqliteConnection(
    file: Path
) = Database.connect(
    url = "jdbc:sqlite:${file.absolutePathString()}",
    driver = "org.sqlite.JDBC"
)