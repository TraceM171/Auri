package com.auri.core.common

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
/**
 * This annotation is used to mark a class, usually an interface, as an extension point.
 * An extension point is a point in the code where a developer can add new functionality by
 * implementing the interface marked with this annotation.
 *
 * This is only a marker annotation and does not have any effect on the code.
 */
annotation class ExtensionPoint
