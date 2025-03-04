package com.auri.app.common.data.entity

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.date

internal object RawSampleTable : IntIdTable() {
    val md5 = text("md5")
    val sha1 = text("sha1")
    val sha256 = text("sha256")
    val path = text("path")
    val collectionDate = date("collection_date")
    val submissionDate = date("submission_date").nullable()
    val name = text("name").nullable()
    val sourceName = text("source_name").nullable()
    val sourceVersion = text("source_version").nullable()
}

internal class RawSampleEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<RawSampleEntity>(RawSampleTable)

    var md5 by RawSampleTable.md5
    var sha1 by RawSampleTable.sha1
    var sha256 by RawSampleTable.sha256
    var path by RawSampleTable.path
    var collectionDate by RawSampleTable.collectionDate
    var submissionDate by RawSampleTable.submissionDate
    var name by RawSampleTable.name
    var sourceName by RawSampleTable.sourceName
    var sourceVersion by RawSampleTable.sourceVersion
}