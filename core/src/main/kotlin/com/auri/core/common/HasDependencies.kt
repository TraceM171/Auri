package com.auri.core.common

/**
 * Interface for classes that have dependencies that need to be checked before they can be used.
 */
interface HasDependencies {
    /**
     * Check if all dependencies are present.
     *
     * @return A list of missing dependencies.
     */
    suspend fun checkDependencies(): List<MissingDependency>
}