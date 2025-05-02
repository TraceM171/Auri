package com.auri.app.common.data.entity

import org.jetbrains.exposed.dao.CompositeEntity
import org.jetbrains.exposed.dao.CompositeEntityClass
import org.jetbrains.exposed.dao.id.CompositeID
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.duration

internal object SampleEvaluationTable : CompositeIdTable() {
    val sampleId = reference(
        name = "sample_id",
        foreign = RawSampleTable,
        onDelete = ReferenceOption.CASCADE,
        onUpdate = ReferenceOption.CASCADE
    )
    val vendor = text("vendor").entityId()
    val checkDate = date("check_date")
    val timeToDetect = duration("time_to_detect")
    val isInmune = bool("is_inmune")
    val isInmuneReason = text("is_inmune_reason")

    init {
        addIdColumn(sampleId)
    }

    override val primaryKey: PrimaryKey?
        get() = PrimaryKey(sampleId, vendor)
}

internal class SampleEvaluationEntity(id: EntityID<CompositeID>) : CompositeEntity(id) {
    companion object : CompositeEntityClass<SampleEvaluationEntity>(SampleEvaluationTable) {
        fun id(sampleId: Int, vendor: String) = CompositeID {
            it[SampleEvaluationTable.sampleId] = sampleId
            it[SampleEvaluationTable.vendor] = vendor
        }
    }

    var sampleId by SampleEvaluationTable.sampleId
    var vendor by SampleEvaluationTable.vendor
    var checkDate by SampleEvaluationTable.checkDate
    var timeToDetect by SampleEvaluationTable.timeToDetect
    var isInmune by SampleEvaluationTable.isInmune
    var isInmuneReason by SampleEvaluationTable.isInmuneReason
}