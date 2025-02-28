package com.auri.extensions.collection

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import co.touchlab.kermit.Logger
import com.auri.core.collection.Collector
import com.auri.core.collection.RawCollectedSample
import com.auri.core.common.util.MagicNumber
import com.auri.core.common.util.PeriodicActionConfig
import com.auri.core.common.util.magicNumber
import com.auri.core.common.util.perform
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.ktor.utils.io.streams.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.URL
import java.time.LocalDate
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class MalShareCollector(
    private val definition: Definition
) : Collector {
    override val name: String = definition.customName
    override val description: String = """
        Collect free daily samples from MalShare.
        Does not allow filtering.
        Limitations:
        - 2000 api calls per day (including downloads, searches, etc.)
    """.trimIndent()
    override val version: String = "0.0.1"

    data class Definition(
        val customName: String = "MalShare",
        val periodicity: PeriodicActionConfig? = PeriodicActionConfig(
            performEvery = 1.days,
            maxRetriesPerPerform = 3,
            skipPerformIfFailed = true,
            retryEvery = 5.minutes
        ),
        val endpointUrl: URL = URI.create("https://malshare.com/api.php").toURL(),
        val apiKey: String,
        val samplesMagicNumberFilter: List<MagicNumber> = MagicNumber.entries,
    )


    private val api = Api(
        endpointUrl = definition.endpointUrl,
        apiKey = definition.apiKey
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun samples(
        collectionParameters: Collector.CollectionParameters
    ): Flow<RawCollectedSample> = if (definition.periodicity == null) singleSamples(collectionParameters)
    else definition.periodicity.perform<Unit, Flow<RawCollectedSample>> {
        singleSamples(collectionParameters)
    }.flatMapConcat {
        (it.getOrNull() ?: emptyFlow())
    }

    private fun singleSamples(
        collectionParameters: Collector.CollectionParameters
    ): Flow<RawCollectedSample> = flow {
        Logger.i { "Getting samples hashes" }
        val samplesHashes = api.samplesHashes().getOrElse {
            Logger.e("Failed to get samples hashes")
            return@flow
        }.also {
            Logger.i { "Found ${it.size} samples hashes" }
        }
        val alreadyDownloadedSamples = collectionParameters.workingDirectory
            .listFiles()
            .map { it.name }
            .toSet()
        Logger.i { "Found ${alreadyDownloadedSamples.size} already downloaded samples" }
        val newSamples = samplesHashes.filter { it.sha1 !in alreadyDownloadedSamples }
        Logger.i { "Found ${newSamples.size} new samples" }
        samplesHashes.forEach { sampleHashes ->
            val destination = File(collectionParameters.workingDirectory, sampleHashes.sha1)
            if (sampleHashes in newSamples) {
                Logger.i { "Downloading sample ${sampleHashes.sha1}" }
                api.downloadSample(sampleHashes, destination).getOrElse {
                    Logger.e { "Failed to download sample ${sampleHashes.sha1}" }
                    return@forEach
                }
                Logger.i { "Successfully downloaded sample ${sampleHashes.sha1}" }
            }
            if (destination.magicNumber() !in definition.samplesMagicNumberFilter) {
                Logger.i { "Skipping sample ${sampleHashes.sha1} because of its magic number" }
                return@forEach
            }
            emit(
                RawCollectedSample(
                    submissionDate = LocalDate.now().toKotlinLocalDate(),
                    name = destination.nameWithoutExtension,
                    executable = destination
                )
            )
        }
    }

    private class Api(
        private val endpointUrl: URL,
        private val apiKey: String,
    ) : AutoCloseable {
        private val client = HttpClient {
            install(HttpTimeout) {
                socketTimeoutMillis = 5.minutes.inWholeMilliseconds
            }
            install(DefaultRequest) {
                url {
                    takeFrom(endpointUrl)
                    parameters.append("api_key", apiKey)
                }
            }
        }

        suspend fun samplesHashes(): Either<Unit, List<SampleLink>> = either {
            val response = client.get {
                parameter("action", "getlist")
            }
            ensure(response.status.isSuccess()) {
                Logger.e { "Failed to list hashes from past 24 hours: ${response.status}" }
            }

            catch(
                {
                    Json.decodeFromString<JsonArray>(response.bodyAsText()).map {
                        val hashesObject = it.jsonObject
                        SampleLink(
                            md5 = hashesObject["md5"]!!.jsonPrimitive.content,
                            sha1 = hashesObject["sha1"]!!.jsonPrimitive.content,
                            sha256 = hashesObject["sha256"]!!.jsonPrimitive.content
                        )
                    }
                },
                {
                    Logger.e { "Failed to parse hashes from past 24 hours" }
                    raise(Unit)
                }
            )
        }

        suspend fun downloadSample(
            sampleLink: SampleLink,
            destination: File
        ): Either<Unit, Unit> = either {
            destination.parentFile.mkdirs()
            val response = client.get {
                parameter("action", "getfile")
                parameter("hash", sampleLink.sha1)
            }
            ensure(response.status.isSuccess()) {
                Logger.e { "Failed to download sample ${sampleLink.sha1}: ${response.status}" }
            }
            val body = response.bodyAsChannel()
            destination.outputStream().asByteWriteChannel().use { body.copyTo(this) }
        }

        override fun close() {
            client.close()
        }

        data class SampleLink(
            val md5: String,
            val sha1: String,
            val sha256: String
        )
    }
}
