package com.auri.app.common.data.entity

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.kotlin.datetime.date

internal object SampleLivenessCheckTable : IntIdTable() {
    val sampleId = reference(
        name = "sample_id",
        foreign = RawSampleTable,
        onDelete = ReferenceOption.CASCADE,
        onUpdate = ReferenceOption.CASCADE
    )
    val checkDate = date("check_date")
    val isAlive = bool("is_alive")
    val isAliveReason = text("is_alive_reason")
}

internal class SampleLivenessCheckEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SampleLivenessCheckEntity>(SampleLivenessCheckTable)

    var sampleId by SampleLivenessCheckTable.sampleId
    var checkDate by SampleLivenessCheckTable.checkDate
    var isAlive by SampleLivenessCheckTable.isAlive
    var isAliveReason by SampleLivenessCheckTable.isAliveReason
}