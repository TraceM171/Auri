package com.auri.common.data.entity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.dao.CompositeEntity
import org.jetbrains.exposed.dao.CompositeEntityClass
import org.jetbrains.exposed.dao.id.CompositeID
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.json.json
import org.jetbrains.exposed.sql.kotlin.datetime.date

//create table sample_info
//(
//    sample_id      INTEGER not null
//        constraint sample_info_raw_sample_id_fk
//            references raw_sample,
//    source_name    TEXT    not null,
//    hash_matched   TEXT,
//    malware_family TEXT,
//    extra_info     TEXT,
//    fetch_date     TEXT    not null,
//    priority       integer not null,
//    constraint sample_info_pk
//        primary key (sample_id, source_name)
//);

object SampleInfoTable : CompositeIdTable() {
    val sampleId = reference(
        name = "sample_id",
        foreign = RawSampleTable,
        onDelete = ReferenceOption.CASCADE,
        onUpdate = ReferenceOption.CASCADE
    )
    val sourceName = text("source_name").entityId()
    val hashMatched = text("hash_matched").nullable()
    val malwareFamily = text("malware_family").nullable()
    val extraInfo = json<JsonObject>("extra_info", Json.Default).nullable()
    val fetchDate = date("fetch_date")
    val priority = integer("priority")

    init {
        addIdColumn(sampleId)
    }

    override val primaryKey: PrimaryKey?
        get() = PrimaryKey(sampleId, sourceName)
}

class SampleInfoEntity(id: EntityID<CompositeID>) : CompositeEntity(id) {
    companion object : CompositeEntityClass<SampleInfoEntity>(SampleInfoTable) {
        fun id(sampleId: Int, sourceName: String) = CompositeID {
            it[SampleInfoTable.sampleId] = sampleId
            it[SampleInfoTable.sourceName] = sourceName
        }
    }

    var hashMatched by SampleInfoTable.hashMatched
    var malwareFamily by SampleInfoTable.malwareFamily
    var extraInfo by SampleInfoTable.extraInfo.transform<JsonObject?, Map<String, String>?>(
        unwrap = { it?.run { JsonObject(mapValues { JsonPrimitive(it.value) }) } },
        wrap = { it?.run { mapValues { it.value.jsonPrimitive.content } } }
    )
    var fetchDate by SampleInfoTable.fetchDate
    var priority by SampleInfoTable.priority
}