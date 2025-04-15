package com.auri.extensions.collection

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.ensure
import co.touchlab.kermit.Logger
import com.auri.core.collection.Collector
import com.auri.core.collection.CollectorStatus
import com.auri.core.collection.CollectorStatus.*
import com.auri.core.collection.RawCollectedSample
import com.auri.core.common.util.*
import com.auri.extensions.collection.common.periodicCollection
import io.ktor.client.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.ktor.utils.io.streams.*
import it.skrape.core.htmlDocument
import it.skrape.selects.eachHref
import it.skrape.selects.html5.a
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toKotlinLocalDate
import java.net.URI
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class VirusSignCollector(
    private val definition: Definition
) : Collector {
    override val name: String = definition.customName
    override val description: String = "Collect free daily malware samples from VirusSign.com"
    override val version: String = "0.0.1"

    data class Definition(
        val customName: String = "VirusSign",
        val periodicity: PeriodicActionConfig? = PeriodicActionConfig(
            performEvery = 1.days,
            maxRetriesPerPerform = 3,
            skipPerformIfFailed = true,
            retryEvery = 5.minutes
        ),
        val directoryListingUrl: URL = URI.create("http://freelist.virussign.com/freelist/").toURL(),
        val apiUser: String,
        val apiPassword: String,
        val samplesPassword: String = "virussign",
        val maxDaysOld: Int? = null,
        val maxDepth: Int = 1,
        val samplesMagicNumberFilter: List<MagicNumber> = MagicNumber.entries,
    )


    private val api = Api(
        directoryListingUrl = definition.directoryListingUrl,
        apiUser = definition.apiUser,
        apiPassword = definition.apiPassword,
        maxDepth = definition.maxDepth
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start(
        workingDirectory: Path,
        checkSampleExistence: suspend (HashAlgorithms, String) -> Boolean
    ): Flow<CollectorStatus> = periodicCollection(
        periodicity = definition.periodicity,
        singleCollection = { singleSamples(workingDirectory) },
    )

    @OptIn(ExperimentalPathApi::class)
    private fun singleSamples(
        workingDirectory: Path
    ): Flow<CollectorStatus> = flow {
        emit(Downloading(what = "Latest samples list"))
        val samplesLinks = api.samplesLinks().getOrElse {
            emit(Failed(what = "Query latest samples", why = it))
            return@flow
        }.also {
            Logger.i { "Found ${it.size} samples links" }
        }.chainIfNotNull(definition.maxDaysOld) { maxDaysOld ->
            Logger.i { "Filtering samples older than $maxDaysOld days" }
            filter {
                (it.date ?: return@filter false).daysUntil(java.time.LocalDate.now().toKotlinLocalDate()) <= maxDaysOld
            }.also {
                Logger.i { "Found ${it.size} samples links" }
            }
        }
        emit(Processing(what = "Already downloaded samples"))
        val alreadyDownloadedUrlsFile = Path(workingDirectory.pathString, "already_downloaded.txt")
            .apply { if (!exists()) createFile() }
        val alreadyDownloadedUrls = alreadyDownloadedUrlsFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        Logger.i { "Found ${alreadyDownloadedUrls.size} already downloaded samples" }
        val newSamples = samplesLinks.filter { it.url.toString() !in alreadyDownloadedUrls }
        Logger.i { "Found ${newSamples.size} new samples" }
        newSamples.mapNotNull { sampleLink ->
            val destination = workingDirectory.resolve(Path(sampleLink.url.path).name)
            emit(Processing(what = "Sample ${destination.nameWithoutExtension}"))
            emit(Downloading(what = "Sample ${destination.nameWithoutExtension}"))
            api.downloadSample(sampleLink, destination).getOrElse {
                Logger.e { "Failed to download sample from ${sampleLink.url}: $it" }
                return@mapNotNull null
            }
            alreadyDownloadedUrlsFile.appendText("${sampleLink.url}\n")
            Logger.i { "Successfully downloaded sample from ${sampleLink.url}" }
            val extractedDir = workingDirectory.resolve("${destination.nameWithoutExtension}_extracted")
            extractSamples(destination, extractedDir).forEach { sample ->
                val rawSample = RawCollectedSample(
                    submissionDate = sampleLink.date,
                    name = sample.nameWithoutExtension,
                    executable = sample
                )
                emit(NewSample(rawSample))
            }
            extractedDir.deleteRecursively()
        }
    }

    private fun extractSamples(
        zipFile: Path,
        destinationDir: Path,
    ): List<Path> {
        if (!destinationDir.exists()) {
            Logger.d { "Extracting ${zipFile.nameWithoutExtension} with password ${definition.samplesPassword}" }
            zipFile.unzip(destinationDirectory = destinationDir, password = definition.samplesPassword)
            zipFile.deleteExisting()
            Logger.d { "Successfully extracted ${zipFile.name}" }
        }
        Logger.d { "Searching for extracted executables" }
        val executables = destinationDir.walk()
            .filter { file -> file.magicNumber() in definition.samplesMagicNumberFilter }
            .toList()
        if (executables.isEmpty())
            Logger.d { "No executable found for sample ${zipFile.nameWithoutExtension}" }
        else
            Logger.d { "Found ${executables.size} executables for sample ${zipFile.nameWithoutExtension}" }
        return executables
    }


    private class Api(
        private val directoryListingUrl: URL,
        private val apiUser: String,
        private val apiPassword: String,
        private val maxDepth: Int
    ) : AutoCloseable {
        private val client = HttpClient {
            install(Auth) {
                basic {
                    credentials {
                        BasicAuthCredentials(apiUser, apiPassword)
                    }
                }
            }
        }

        suspend fun samplesLinks(): Either<String, List<SampleLink>> = either {
            val baseUrl = directoryListingUrl.toString().removeSuffix(directoryListingUrl.path)
            val dateRegex = Regex("""\d{8}|\d{6}""")
            val rawLinksList = getLinksInPath(null, 0).bind()
            Logger.i { "Found ${rawLinksList.size} links" }
            rawLinksList.mapNotNull {
                val dateString = dateRegex.find(it)?.value ?: return@mapNotNull SampleLink(URI.create(it).toURL())
                val date = when (dateString.length) {
                    6 -> LocalDate(
                        year = dateString.substring(0, 2).toInt() + 2000,
                        monthNumber = dateString.substring(2, 4).toInt(),
                        dayOfMonth = dateString.substring(4).toInt()
                    )

                    8 -> LocalDate(
                        year = dateString.substring(0, 4).toInt(),
                        monthNumber = dateString.substring(4, 6).toInt(),
                        dayOfMonth = dateString.substring(6).toInt()
                    )

                    else -> return@mapNotNull null
                }
                SampleLink(URI.create("$baseUrl$it").toURL(), date)
            }.sortedBy { it.date }
        }

        suspend fun downloadSample(
            sampleLink: SampleLink,
            destination: Path
        ): Either<String, Unit> = either {
            destination.parent.createDirectories()
            val response = client.get(sampleLink.url.toString())
            ensure(response.status.isSuccess()) {
                "Failed to download sample: ${response.status}"
            }
            val body = response.bodyAsChannel()
            destination.outputStream().asByteWriteChannel().use { body.copyTo(this) }
        }

        private suspend fun getLinksInPath(
            path: String?,
            currentDepth: Int
        ): Either<String, List<String>> = either {
            val response = client.get {
                url {
                    takeFrom(directoryListingUrl)
                    path?.let { path(it) }
                }
            }
            ensure(response.status.isSuccess()) {
                "Failed to fetch directory listing: ${response.status}"
            }

            val body = response.bodyAsText()
            val allLinks = htmlDocument(body) {
                a { findAll { eachHref } }
            }
            buildList {
                allLinks.forEach {
                    when {
                        it.endsWith("/") && it != "/" && currentDepth < maxDepth ->
                            getLinksInPath(it, currentDepth + 1).onRight(::addAll)

                        it.endsWith(".zip") -> add(it)
                    }
                }
            }
        }

        override fun close() {
            client.close()
        }

        data class SampleLink(
            val url: URL,
            val date: LocalDate? = null
        )
    }
}
