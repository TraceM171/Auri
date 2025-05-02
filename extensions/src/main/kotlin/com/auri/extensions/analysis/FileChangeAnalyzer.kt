package com.auri.extensions.analysis

import arrow.core.*
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import co.touchlab.kermit.Logger
import com.auri.core.analysis.Analyzer
import com.auri.core.analysis.ChangeReport
import com.auri.core.analysis.VMInteraction
import com.auri.core.common.util.ctx
import com.auri.core.common.util.failure
import com.auri.core.common.util.getResource
import com.auri.core.common.util.messageWithCtx
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.selects.select
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class FileChangeAnalyzer(
    private val definition: Definition
) : Analyzer {
    override val name: String = definition.customName
    override val description: String = "Flags changes in a set of defined files"
    override val version: String = "0.0.1"

    data class Definition(
        val customName: String = "File Changes",
        val files: List<VMFilePath>,
        val featuresToTrack: List<FileFeature> = listOf(
            FileFeature.Size,
            FileFeature.Created,
            FileFeature.LastModified,
            FileFeature.Attributes,
            FileFeature.Hash
        )
    ) {
        init {
            require(FileFeature.LastAccessed !in featuresToTrack || FileFeature.Hash !in featuresToTrack) {
                "LastAccessed and Hash features are not supported together, as Hash calculation updates LastAccessed"
            }
        }
    }

    private val initialStates: MutableMap<Analyzer.StateKey, Map<VMFilePath, FileState>> = mutableMapOf()

    override suspend fun captureInitialState(
        workingDirectory: Path,
        interaction: VMInteraction,
        stateKey: Analyzer.StateKey
    ): Either<Throwable, Unit> = either {
        getCurrentState(interaction)
            .ctx("Getting initial state")
            .bind()
            .let { initialStates.put(stateKey, it) }
    }

    override suspend fun reportChanges(
        workingDirectory: Path,
        interaction: VMInteraction,
        stateKey: Analyzer.StateKey
    ): Either<Throwable, ChangeReport> = either {
        val initialState = initialStates[stateKey]
        ensureNotNull(initialState) {
            failure("Tried to report changed based on a non-existing initial stated (key=${stateKey.value})")
        }
        val currentState = getCurrentState(interaction).getOrElse {
            Logger.i { "Failed to get current state, marking as AccessLost: ${it.messageWithCtx}" }
            return@either ChangeReport.AccessLost
        }
        val changesFound = initialState.flatMap { (path, initialFeatures) ->
            run {
                val currentFeatures = currentState[path] ?: return@run listOf("File was deleted")
                (currentFeatures differencesWith initialFeatures).orEmpty()
            }.map { "(${path.value}) $it" }
        }.toNonEmptyListOrNull() ?: return@either ChangeReport.NotChanged
        ChangeReport.Changed(what = changesFound)
    }

    private suspend fun getCurrentState(interaction: VMInteraction) = either {
        val script = getResource("analysis/file_change_analyzer_check.ps1")!!
            .bufferedReader()
            .use(BufferedReader::readText)
        val command = interaction.prepareCommand(script)
        coroutineScope {
            val job = Job()
            val result = select {
                async(job) { command.run() }.onAwait(ScriptResult::RunEnded)
                async(job) {
                    command.commandError.consumeAsFlow().firstOrNull()
                        .also { if (it == null) delay(5.seconds) }
                }.onAwait(ScriptResult::ConsoleErrorReceived)
                async(job) {
                    definition.files.map { path ->
                        command.commandInput.send("${path.value}\n")
                        command.commandOutput.receive().let(::parseScriptResult)
                            .getOrElse {
                                Logger.e { "Failed to parse script result" }
                                error("Failed to parse script result")
                            }
                    }.reduce { acc, it ->
                        acc + it
                    }
                }.onAwait(ScriptResult::AllFilesChecked)
            }
            job.cancel()
            when (result) {
                is ScriptResult.RunEnded -> result.result
                    .flatMap { Throwable("Script finished before checking all files").left() }

                is ScriptResult.ConsoleErrorReceived -> Throwable("Script sent an error message: ${result.error}")
                    .left()

                is ScriptResult.AllFilesChecked -> result.data.right()
            }.ctx("Running script to check file changes")
                .bind()
        }
    }

    private fun parseScriptResult(scriptResult: String) = either {
        val resultJson = catch(
            { Json.parseToJsonElement(scriptResult).jsonArray.map { it.jsonObject } },
            { raise(Unit) }
        )
        resultJson.associate { jsonObject ->
            val path = jsonObject.getOrElse("FilePath") { raise(Unit) }
                .run {
                    catch(
                        { jsonPrimitive },
                        { raise(Unit) }
                    )
                }.jsonPrimitive
                .content
                .let(::VMFilePath)
            path to FileState(
                size = jsonObject
                    .getOrElse("Size") { raise(Unit) }
                    .run {
                        catch(
                            { jsonPrimitive },
                            { raise(Unit) }
                        )
                    }.jsonPrimitive
                    .content
                    .toLong()
                    .let { FileFeatureState.SizeState(it) }
                    .takeIf { FileFeature.Size in definition.featuresToTrack },
                created = jsonObject
                    .getOrElse("CreationTime") { raise(Unit) }
                    .run {
                        catch(
                            { jsonPrimitive },
                            { raise(Unit) }
                        )
                    }.jsonPrimitive
                    .content
                    .let { Instant.parse(it) }
                    .let { FileFeatureState.CreatedState(it) }
                    .takeIf { FileFeature.Created in definition.featuresToTrack },
                lastModified = jsonObject
                    .getOrElse("LastModified") { raise(Unit) }
                    .run {
                        catch(
                            { jsonPrimitive },
                            { raise(Unit) }
                        )
                    }.jsonPrimitive
                    .content
                    .let { Instant.parse(it) }
                    .let { FileFeatureState.LastModifiedState(it) }
                    .takeIf { FileFeature.LastModified in definition.featuresToTrack },
                lastAccessed = jsonObject
                    .getOrElse("LastAccessTime") { raise(Unit) }
                    .run {
                        catch(
                            { jsonPrimitive },
                            { raise(Unit) }
                        )
                    }.jsonPrimitive
                    .content
                    .let { Instant.parse(it) }
                    .let { FileFeatureState.LastAccessedState(it) }
                    .takeIf { FileFeature.LastAccessed in definition.featuresToTrack },
                attributes = jsonObject
                    .getOrElse("Attributes") { raise(Unit) }
                    .run {
                        catch(
                            { jsonPrimitive },
                            { raise(Unit) }
                        )
                    }.jsonPrimitive
                    .content
                    .split(',')
                    .toSet()
                    .let { FileFeatureState.AttributesState(it) }
                    .takeIf { FileFeature.Attributes in definition.featuresToTrack },
                hash = jsonObject
                    .getOrElse("Hash") { raise(Unit) }
                    .run {
                        catch(
                            { jsonPrimitive },
                            { raise(Unit) }
                        )
                    }.jsonPrimitive
                    .content
                    .let { FileFeatureState.HashState(it) }
                    .takeIf { FileFeature.Hash in definition.featuresToTrack }
            )
        }
    }

    private sealed interface ScriptResult {
        data class RunEnded(val result: Either<Throwable, Int>) : ScriptResult
        data class ConsoleErrorReceived(val error: String?) : ScriptResult
        data class AllFilesChecked(val data: Map<VMFilePath, FileState>) : ScriptResult
    }

    @JvmInline
    value class VMFilePath(val value: String)

    sealed interface FileFeature {
        data object Size : FileFeature
        data object Created : FileFeature
        data object LastModified : FileFeature
        data object LastAccessed : FileFeature
        data object Attributes : FileFeature
        data object Hash : FileFeature
    }

    private interface FileFeatureState<T : FileFeatureState<T>> {
        infix fun differenceWith(before: T): String?

        data class SizeState(
            val sizeBytes: Long
        ) : FileFeatureState<SizeState> {
            override fun differenceWith(before: SizeState): String? = when {
                sizeBytes > before.sizeBytes -> "File size increased by ${sizeBytes - before.sizeBytes} bytes"
                sizeBytes < before.sizeBytes -> "File size decreased by ${before.sizeBytes - sizeBytes} bytes"
                else -> null
            }
        }

        data class CreatedState(
            val created: Instant
        ) : FileFeatureState<CreatedState> {
            override fun differenceWith(before: CreatedState): String? = when {
                created > before.created -> "File was created ${created - before.created} after"
                created < before.created -> "File was created ${before.created - created} before"
                else -> null
            }
        }

        data class LastModifiedState(
            val lastModified: Instant
        ) : FileFeatureState<LastModifiedState> {
            override fun differenceWith(before: LastModifiedState): String? = when {
                lastModified > before.lastModified -> "File was modified ${lastModified - before.lastModified} after"
                lastModified < before.lastModified -> "File was modified ${before.lastModified - lastModified} before"
                else -> null
            }
        }

        data class LastAccessedState(
            val lastAccessed: Instant
        ) : FileFeatureState<LastAccessedState> {
            override fun differenceWith(before: LastAccessedState): String? = when {
                lastAccessed > before.lastAccessed -> "File was accessed ${lastAccessed - before.lastAccessed} after"
                lastAccessed < before.lastAccessed -> "File was accessed ${before.lastAccessed - lastAccessed} before"
                else -> null
            }
        }

        data class AttributesState(
            val attributes: Set<String>
        ) : FileFeatureState<AttributesState> {
            override fun differenceWith(before: AttributesState): String? = when {
                attributes != before.attributes -> "File attributes changed from ${before.attributes} to $attributes"
                else -> null
            }
        }

        data class HashState(
            val hash: String
        ) : FileFeatureState<HashState> {
            override fun differenceWith(before: HashState): String? = when {
                hash != before.hash -> "File hash changed from ${before.hash} to $hash"
                else -> null
            }
        }
    }

    private data class FileState(
        val size: FileFeatureState.SizeState?,
        val created: FileFeatureState.CreatedState?,
        val lastModified: FileFeatureState.LastModifiedState?,
        val lastAccessed: FileFeatureState.LastAccessedState?,
        val attributes: FileFeatureState.AttributesState?,
        val hash: FileFeatureState.HashState?
    ) {
        infix fun differencesWith(before: FileState): Nel<String>? = buildList {
            before.size?.let(this@FileState.size!!::differenceWith)?.let(::add)
            before.created?.let(created!!::differenceWith)?.let(::add)
            before.lastModified?.let(lastModified!!::differenceWith)?.let(::add)
            before.lastAccessed?.let(lastAccessed!!::differenceWith)?.let(::add)
            before.attributes?.let(attributes!!::differenceWith)?.let(::add)
            before.hash?.let(hash!!::differenceWith)?.let(::add)
        }.toNonEmptyListOrNull()
    }
}