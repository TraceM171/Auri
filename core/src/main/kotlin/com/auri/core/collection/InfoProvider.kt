package com.auri.core.collection

import com.auri.core.common.ExtensionPoint
import com.auri.core.common.util.HashAlgorithms

/**
 * Interface for a sample info provider.
 *
 * A info provider is a component that, using the hash value of a sample, provides information about that sample.
 */
@ExtensionPoint
interface InfoProvider {
    /**
     * The name of the info provider. Must be unique for each info provider.
     */
    val name: String

    /**
     * A description of the info provider.
     */
    val description: String

    /**
     * The version of the info provider.
     */
    val version: String

    /**
     * Get the info for a sample with the given hash value. If the sample is not found, null is returned.
     *
     * @param getHashValue A function that returns the hash value of a sample for a given hash algorithm.
     * @return The sample info for the sample with the given hash value if found.
     */
    suspend fun sampleInfoByHash(
        getHashValue: (HashAlgorithms) -> String
    ): SampleInfo?
}