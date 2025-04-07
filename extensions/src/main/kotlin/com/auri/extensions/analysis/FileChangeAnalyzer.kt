package com.auri.extensions.analysis

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.catch
import arrow.core.raise.either
import co.touchlab.kermit.Logger
import com.auri.core.analysis.Analyzer
import com.auri.core.analysis.ChangeReport
import com.auri.core.analysis.VMInteraction
import com.auri.core.common.util.getResource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File

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
            FileFeature.Existence,
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

    private var initialState: Map<VMFilePath, Set<FileFeatureState>> = mapOf()

    override suspend fun captureInitialState(
        workingDirectory: File,
        interaction: VMInteraction
    ): Either<Unit, Unit> = either {
        initialState = getCurrentState(interaction).bind()
    }

    override suspend fun reportChanges(
        workingDirectory: File,
        interaction: VMInteraction
    ): Either<Unit, ChangeReport> = either {
        val currentState = getCurrentState(interaction).getOrElse { return@either ChangeReport.AccessLost }
        val changesFound = currentState.map { (path, currentFeatures) ->
            val initialFeatures = initialState[path] ?: emptySet()
            currentFeatures.map { currentFeature ->
                val initialFeature = initialFeatures.find { it::class == currentFeature::class }
                val hasChanged = initialFeature != currentFeature
                if (hasChanged) {
                    Logger.d { "Change detected in $path: before $initialFeature, after $currentFeature" }
                }
                hasChanged
            }.any { it }
        }.any { it }
        if (changesFound) ChangeReport.Changed else ChangeReport.NotChanged
    }

    private suspend fun getCurrentState(interaction: VMInteraction) = either {
        val script = getResource("analysis/file_change_analyzer_check.ps1")!!
            .bufferedReader()
            .use(BufferedReader::readText)
        val command = interaction.prepareCommand(script)
        catch(
            {
                coroutineScope {
                    val result = async(Job()) { command.run() }
                    launch {
                        command.commandError.consumeAsFlow().collect {
                            Logger.e { "Command error : $it" }
                            result.cancel()
                        }
                    }
                    val state = definition.files.associateWith { path ->
                        command.commandInput.send("${path.value}\n")
                        command.commandOutput.receive().let(::parseScriptResult)
                            .onLeft {
                                Logger.e { "Failed to parse script result" }
                            }.bind()
                    }
                    result.cancelAndJoin()
                    state
                }
            },
            { raise(Unit) }
        )
    }

    private fun parseScriptResult(scriptResult: String) = either {
        val resultJson = catch(
            { Json.parseToJsonElement(scriptResult).jsonObject },
            { raise(Unit) }
        )
        buildSet<FileFeatureState> {
            resultJson
                .getOrElse("Exists") { raise(Unit) }
                .run {
                    catch(
                        { jsonPrimitive },
                        { raise(Unit) }
                    )
                }.jsonPrimitive
                .content
                .toBoolean()
                .let { FileFeatureState.ExistenceState(it) }
                .also(::add)
                .also {
                    if (!it.exists) return@buildSet
                }
            resultJson
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
                .let(::add)
            resultJson
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
                .let(::add)
            resultJson
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
                .let(::add)
            resultJson
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
                .let(::add)
            resultJson
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
                .let(::add)
            resultJson
                .getOrElse("Hash") { raise(Unit) }
                .run {
                    catch(
                        { jsonPrimitive },
                        { raise(Unit) }
                    )
                }.jsonPrimitive
                .content
                .let { FileFeatureState.HashState(it) }
                .let(::add)
        }
    }

    @JvmInline
    value class VMFilePath(val value: String)

    sealed interface FileFeature {
        data object Existence : FileFeature
        data object Size : FileFeature
        data object Created : FileFeature
        data object LastModified : FileFeature
        data object LastAccessed : FileFeature
        data object Attributes : FileFeature
        data object Hash : FileFeature
    }

    private interface FileFeatureState {
        data class ExistenceState(
            val exists: Boolean
        ) : FileFeatureState

        data class SizeState(
            val sizeBytes: Long
        ) : FileFeatureState

        data class CreatedState(
            val created: Instant
        ) : FileFeatureState

        data class LastModifiedState(
            val lastModified: Instant
        ) : FileFeatureState

        data class LastAccessedState(
            val lastAccessed: Instant
        ) : FileFeatureState

        data class AttributesState(
            val attributes: Set<String>
        ) : FileFeatureState

        data class HashState(
            val hash: String
        ) : FileFeatureState
    }
}