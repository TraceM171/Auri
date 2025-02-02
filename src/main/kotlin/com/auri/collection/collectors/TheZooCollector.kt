package com.auri.collection.collectors

import arrow.core.mapValuesNotNull
import arrow.core.raise.either
import co.touchlab.kermit.Logger
import com.auri.collection.definition.Collector
import com.auri.collection.definition.RawCollectedSample
import com.auri.core.*
import com.auri.core.data.sqliteConnection
import com.auri.core.util.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.toKotlinLocalDate
import net.lingala.zip4j.ZipFile
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.net.URI
import java.net.URL
import java.time.format.DateTimeFormatter

class TheZooCollector(
    private val definition: Definition
) : Collector {
    override val name: String = definition.customName
    override val description: String = "Collect malware samples from theZoo repository"
    override val version: String = "0.0.1"

    data class Definition(
        val customName: String = "TheZoo",
        val repoUrl: URL = URI.create("https://github.com/ytisf/theZoo.git").toURL(),
        val repoBranch: String = "master",
        val samplesTypeFilter: Regex = Regex(".*"),
        val samplesArchFilter: Regex = Regex(".*"),
        val samplesPlatformFilter: Regex = Regex(".*"),
        val samplesMagicNumberFilter: List<MagicNumber> = MagicNumber.entries,
    )


    override fun samples(
        collectionParameters: Collector.CollectionParameters
    ): Flow<RawCollectedSample> = flow {
        val theZooFolder = File(collectionParameters.workingDirectory, name).apply { mkdirs() }
        cloneRepo(collectionParameters, theZooFolder).onLeft {
            return@flow
        }

        val theZooDB = File(theZooFolder, "conf/maldb.db").let(::sqliteConnection)
        val filteredSamples = theZooDB.getMalwareSamples()

        val extractedSamples = filteredSamples.associateWith { sample ->
            val sampleDir = File(theZooFolder, sample.location)
            if (!sampleDir.exists()) {
                Logger.e { "Sample directory ${sampleDir.name} does not exist" }
                return@associateWith null
            }
            extractSample(sampleDir)
        }.mapValuesNotNull { it.value?.takeUnless(List<File>::isEmpty) }

        val rawSamples = extractedSamples.mapNotNull { (sample, files) ->
            files.map {
                RawCollectedSample(
                    name = sample.name,
                    submissionDate = sample.date,
                    executable = it
                )
            }
        }.flatten()

        emitAll(rawSamples.asFlow())
    }

    private suspend fun cloneRepo(
        collectionParameters: Collector.CollectionParameters,
        repoFolder: File,
    ) = either {
        val repoRoot = runNativeCommand(
            workingDir = repoFolder,
            "git",
            "rev-parse",
            "--show-toplevel"
        ).getOrNull()
        val existsRepo = repoRoot?.let { File(it) } == repoFolder
        if (existsRepo) {
            if (collectionParameters.invalidateCache) {
                Logger.d { "Removing theZoo repository cache" }
                repoFolder.deleteRecursively()
            } else {
                Logger.d { "theZoo repository already exists" }
                return@either
            }
        }
        Logger.d { "Cloning theZoo repository ($definition.repoUrl) to $repoFolder" }
        runNativeCommand(
            workingDir = collectionParameters.workingDirectory,
            "git",
            "clone",
            definition.repoUrl.toString(),
            repoFolder.absolutePath,
            "--branch",
            definition.repoBranch
        ).onLeft {
            Logger.e { "Failed to clone theZoo repository: $it" }
        }.mapLeft { }.bind()
        Logger.d { "Successfully cloned theZoo repository" }
    }

    private fun Database.getMalwareSamples(): List<MalwareEntity> {
        Logger.d { "Querying theZoo database for malware samples" }
        val samples = transaction(this) {
            MalwareEntity.all().toList()
        }.filter {
            it.type containsMatch this@TheZooCollector.definition.samplesTypeFilter.withMultilineOption()
                    && it.architecture containsMatch this@TheZooCollector.definition.samplesArchFilter.withMultilineOption()
                    && it.platform containsMatch this@TheZooCollector.definition.samplesPlatformFilter.withMultilineOption()
        }
        Logger.d { "Found ${samples.size} malware samples matching the filters" }
        return samples
    }

    private fun extractSample(
        sampleDir: File,
    ): List<File> {
        Logger.d { "Extracting sample ${sampleDir.name}" }

        val passFile = sampleDir.listFiles { _, name -> name.endsWith(".pass") }?.firstOrNull() ?: run {
            Logger.e { "No password file found for sample ${sampleDir.name}" }
            return emptyList()
        }
        val pass = passFile.readText().trim()
        val zipFile = sampleDir.listFiles { _, name -> name.endsWith(".zip") }?.firstOrNull() ?: run {
            Logger.e { "No zip file found for sample ${sampleDir.name}" }
            return emptyList()
        }
        Logger.d { "Extracting ${zipFile.name} with password $pass" }
        ZipFile(zipFile, pass.toCharArray())
            .extractAll(sampleDir.absolutePath)
        Logger.d { "Successfully extracted ${zipFile.name}" }
        Logger.d { "Searching for extracted executable" }
        val executables = sampleDir.walkTopDown()
            .filter { file -> file.magicNumber() in this@TheZooCollector.definition.samplesMagicNumberFilter }
            .toList()
        if (executables.isEmpty())
            Logger.e { "No executable found for sample ${sampleDir.name}" }
        else
            Logger.d { "Found ${executables.size} executables for sample ${sampleDir.name}" }
        return executables
    }
}

/*
* CREATE TABLE "Malwares" (
	`ID`	INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
	`LOCATION`	TEXT NOT NULL,
	`TYPE`	TEXT NOT NULL,
	`NAME`	TEXT NOT NULL,
	`VERSION`	TEXT,
	`AUTHOR`	TEXT,
	`LANGUAGE`	TEXT NOT NULL,
	`DATE`	DATE,
	`ARCHITECTURE`	TEXT NOT NULL,
	`PLATFORM`	TEXT NOT NULL,
	`VIP`	BOOLEAN NOT NULL,
	`COMMENTS`	TEXT,
	`TAGS`	TEXT
)
*/
object MalwareTable : IntIdTable(name = "Malwares") {
    val location = varchar("location", 255)
    val type = varchar("type", 255)
    val name = varchar("name", 255)
    val version = varchar("version", 255).nullable()
    val author = varchar("author", 255).nullable()
    val language = varchar("language", 255)
    val date = text("date").nullable()
    val architecture = varchar("architecture", 255)
    val platform = varchar("platform", 255)
    val vip = bool("vip")
    val comments = varchar("comments", 255).nullable()
    val tags = varchar("tags", 255).nullable()
}

@Suppress("unused")
class MalwareEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<MalwareEntity>(MalwareTable)

    var location by MalwareTable.location
    var type by MalwareTable.type
    var name by MalwareTable.name
    var version by MalwareTable.version
    var author by MalwareTable.author
    var language by MalwareTable.language
    var date by MalwareTable.date.run {
        val nullValues = listOf("NA", "N/A")
        val localDateFormats = listOf(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy")
        )
        memoizedTransform(
            unwrap = { it.toString() },
            wrap = { unwrapped ->
                if (unwrapped == null) return@memoizedTransform null
                if (unwrapped in nullValues) return@memoizedTransform null
                val localDateFormat = localDateFormats.firstOrNull { formatter ->
                    runCatching { java.time.LocalDate.parse(unwrapped, formatter) }.isSuccess
                } ?: return@memoizedTransform null
                java.time.LocalDate.parse(unwrapped, localDateFormat).toKotlinLocalDate()
            }
        )
    }
    var architecture by MalwareTable.architecture
    var platform by MalwareTable.platform
    var vip by MalwareTable.vip
    var comments by MalwareTable.comments
    var tags by MalwareTable.tags
}