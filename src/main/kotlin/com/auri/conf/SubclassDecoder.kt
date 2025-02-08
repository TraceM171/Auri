package com.auri.conf

import com.sksamuel.hoplite.*
import com.sksamuel.hoplite.decoder.DataClassDecoder
import com.sksamuel.hoplite.decoder.NullHandlingDecoder
import com.sksamuel.hoplite.fp.invalid
import com.sksamuel.hoplite.fp.valid
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createType

class SubclassDecoder(
    private val classLoader: ClassLoader
) : NullHandlingDecoder<Any> {

    override fun supports(type: KType): Boolean =
        (type.classifier as? KClass<*>)?.isFinal == false

    // it's common to have custom decoders for sealed classes, so this decoder should be very low priority
    override fun priority(): Int = Integer.MIN_VALUE + 90

    override fun safeDecode(
        node: Node,
        type: KType,
        context: DecoderContext
    ): ConfigResult<Any> {
        val field = context.sealedTypeDiscriminatorField ?: "_type"

        val kclass = type.classifier as KClass<*>

        // when explicitly specifying subtypes, we must have a map type containing the discriminator field,
        // or a string type referencing an object instance
        return when (node) {
            is StringNode -> {
                val referencedName = node.value
                val subtype = getKClassForName(referencedName)?.objectInstance
                subtype?.valid() ?: ConfigFailure.NoSealedClassObjectSubtype(kclass, referencedName).invalid()
            }

            is MapNode -> {
                when (val discriminatorField = node[field]) {
                    is StringNode -> {
                        val subtype = getKClassForName(discriminatorField.value)
                        if (subtype == null) {
                            ConfigFailure.NoSuchSealedSubtype(kclass, discriminatorField.value).invalid()
                        } else {
                            // check for object-ness first
                            subtype.objectInstance?.valid()
                            // now we know the type is not an object, we can use the data class decoder directly
                                ?: DataClassDecoder().decode(node, subtype.createType(), context)
                        }
                    }

                    else -> ConfigFailure.InvalidDiscriminatorField(kclass, field).invalid()
                }
            }

            else -> ConfigFailure.Generic("Sealed type values must be maps or strings").invalid()
        }
    }

    private fun getKClassForName(name: String) =
        runCatching { Class.forName(name, true, classLoader) }.getOrNull()?.kotlin
}
