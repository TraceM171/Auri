package com.auri.app.common.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun <T : IntEntity> IntEntityClass<T>.getAsFlow(
    database: Database,
    filter: SqlExpressionBuilder.() -> Op<Boolean> = { Op.TRUE },
    batchSize: Int = 100,
    keepListening: KeepListening? = KeepListening(),
) = flow {
    var lastIdSent = 0
    while (true) {
        val newBatch = newSuspendedTransaction(
            context = Dispatchers.IO,
            db = database
        ) {
            table.selectAll()
                .where { table.id greater lastIdSent }
                .andWhere(filter)
                .orderBy(table.id, SortOrder.ASC)
                .limit(batchSize)
                .let(::wrapRows)
                .toList()
        }
        if (newBatch.isEmpty()) {
            if (keepListening == null) break
            delay(keepListening.pollTime)
            continue
        }
        newBatch.forEach {
            emit(it)
            lastIdSent = it.id.value
        }
    }
}

data class KeepListening(
    val pollTime: Duration = 1.seconds,
)