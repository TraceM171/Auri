package com.auri.core.common

/**
 * A value class to represent a missing dependency that the user needs to resolve
 *
 * @param name The name of the missing dependency.
 * @param version The version of the missing dependency if any is required.
 * @param use How the dependency is used.
 * @param resolution How the user can resolve the missing dependency.
 */
data class MissingDependency(
    val name: String,
    val version: String? = null,
    val use: String,
    val resolution: String
)