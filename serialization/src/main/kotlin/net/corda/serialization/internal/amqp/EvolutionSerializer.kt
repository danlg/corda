package net.corda.serialization.internal.amqp

import net.corda.core.KeepForDJVM
import net.corda.core.internal.isConcreteClass
import net.corda.core.serialization.DeprecatedConstructorForDeserialization
import net.corda.core.serialization.SerializationContext
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.debug
import net.corda.core.utilities.loggerFor
import net.corda.serialization.internal.carpenter.getTypeAsClass
import org.apache.qpid.proton.codec.Data
import java.io.NotSerializableException
import java.lang.reflect.Type
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.jvmErasure


/**
 * Serializer for deserializing objects whose definition has changed since they
 * were serialised.
 *
 * @property oldReaders A linked map representing the properties of the object as they were serialized. Note
 * this may contain properties that are no longer needed by the class. These *must* be read however to ensure
 * any refferenced objects in the object stream are captured properly
 * @property kotlinConstructor
 * @property constructorArgs used to hold the properties as sent to the object's constructor. Passed in as a
 * pre populated array as properties not present on the old constructor must be initialised in the factory
 */
interface EvolutionSerializer: ObjectSerializer {

    /**
     * Represents a parameter as would be passed to the constructor of the class as it was
     * when it was serialised and NOT how that class appears now
     *
     * @param resultsIndex index into the constructor argument list where the read property
     * should be placed
     * @param property object to read the actual property value
     */
    @KeepForDJVM
    data class OldParam(var resultsIndex: Int, val property: PropertySerializer) {
        fun readProperty(obj: Any?, schemas: SerializationSchemas, input: DeserializationInput,
                         new: Array<Any?>, context: SerializationContext
        ) = property.readProperty(obj, schemas, input, context).apply {
            if (resultsIndex >= 0) {
                new[resultsIndex] = this
            }
        }

        override fun toString(): String {
            return "resultsIndex = $resultsIndex property = ${property.name}"
        }
    }

    companion object {
        val logger = contextLogger()

        /**
         * Unlike the generic deserialization case where we need to locate the primary constructor
         * for the object (or our best guess) in the case of an object whose structure has changed
         * since serialisation we need to attempt to locate a constructor that we can use. For example,
         * its parameters match the serialised members and it will initialise any newly added
         * elements.
         *
         * TODO: Type evolution
         * TODO: rename annotation
         */
        private fun getEvolverConstructor(type: Type, oldArgs: Map<String, OldParam>): KFunction<Any>? {
            val clazz: Class<*> = type.asClass()

            if (!clazz.isConcreteClass) return null

            val oldArgumentSet = oldArgs.map { Pair(it.key as String?, it.value.property.resolvedType.asClass()) }
            var maxConstructorVersion = Integer.MIN_VALUE
            var constructor: KFunction<Any>? = null

            clazz.kotlin.constructors.forEach {
                val version = it.findAnnotation<DeprecatedConstructorForDeserialization>()?.version ?: Integer.MIN_VALUE

                if (version > maxConstructorVersion &&
                        oldArgumentSet.containsAll(it.parameters.map { v -> Pair(v.name, v.type.javaType.asClass()) })
                ) {
                    constructor = it
                    maxConstructorVersion = version

                    with(logger) {
                        info("Select annotated constructor version=$version nparams=${it.parameters.size}")
                        debug{"  params=${it.parameters}"}
                    }
                } else if (version != Integer.MIN_VALUE){
                    with(logger) {
                        info("Ignore annotated constructor version=$version nparams=${it.parameters.size}")
                        debug{"  params=${it.parameters}"}
                    }
                }
            }

            // if we didn't get an exact match revert to existing behaviour, if the new parameters
            // are not mandatory (i.e. nullable) things are fine
            return constructor ?: run {
                logger.info("Failed to find annotated historic constructor")
                constructorForDeserialization(type)
            }
        }

        private fun makeWithConstructor(
                typeInformation: TypeInformation,
                objectConstructor: ObjectConstructor,
                readersAsSerialized: Map<String, OldParam>): AMQPSerializer<Any> {
            val constructorArgs = arrayOfNulls<Any?>(objectConstructor.parameterCount)

            // Java doesn't care about nullability unless it's a primitive in which
            // case it can't be referenced. Unfortunately whilst Kotlin does apply
            // Nullability annotations we cannot use them here as they aren't
            // retained at runtime so we cannot rely on the absence of
            // any particular NonNullable annotation type to indicate cross
            // compiler nullability
            val isKotlin = (typeInformation.type.javaClass.declaredAnnotations.any {
                        it.annotationClass.qualifiedName == "kotlin.Metadata"
            })

            objectConstructor.parameters.withIndex().forEach {
                if ((readersAsSerialized[it.value.name!!] ?.apply { this.resultsIndex = it.index }) == null) {
                    // If there is no value in the byte stream to map to the parameter of the constructor
                    // this is ok IFF it's a Kotlin class and the parameter is non nullable OR
                    // its a Java class and the parameter is anything but an unboxed primitive.
                    // Otherwise we throw the error and leave
                    if ((isKotlin && !it.value.type.isMarkedNullable)
                            || (!isKotlin && isJavaPrimitive(it.value.type.jvmErasure.java))
                    ) {
                        throw AMQPNotSerializableException(
                                typeInformation.type,
                                "New parameter \"${it.value.name}\" is mandatory, should be nullable for evolution " +
                                        "to work, isKotlinClass=$isKotlin type=${it.value.type}")
                    }
                }
            }
            return EvolutionSerializerViaConstructor(typeInformation, readersAsSerialized, constructorArgs, objectConstructor)
        }

        private fun makeWithSetters(
                typeInformation: TypeInformation,
                factory: SerializerFactory,
                objectConstructor: ObjectConstructor,
                readersAsSerialized: Map<String, OldParam>,
                classProperties: Map<String, PropertyDescriptor>): AMQPSerializer<Any> {
            val setters = propertiesForSerializationFromSetters(classProperties,
                    typeInformation.type,
                    factory).associateBy({ it.serializer.name }, { it })
            return EvolutionSerializerViaSetters(typeInformation, readersAsSerialized, objectConstructor, setters)
        }

        /**
         * Build a serialization object for deserialization only of objects serialised
         * as different versions of a class.
         *
         * @param old is an object holding the schema that represents the object
         *  as it was serialised and the type descriptor of that type
         * @param new is the Serializer built for the Class as it exists now, not
         * how it was serialised and persisted.
         * @param factory the [SerializerFactory] associated with the serialization
         * context this serializer is being built for
         */
        fun make(old: CompositeType,
                 new: ObjectSerializer,
                 factory: SerializerFactory
        ): AMQPSerializer<Any> {
            // Evolution is triggered by a mismatch in the fingerprint for the entire type,
            // however the actual difference may not be in this type but in the type of one of its properties
            // (or one of its properties properties, etc), in which case we can safely return the serializer
            // we just generated for this type, and let the serializer generated for the property type look
            // after evolving values of that type.
            //
            // The outcome of doing this is that the existing serializer is associated with the new fingerprint
            // as well as the old one, so we won't go looking for an evolution serializer the next time around.
            if (!mustEvolve(old, new)) return new

            // The order in which the properties were serialised is important and must be preserved
            val readersAsSerialized = LinkedHashMap<String, OldParam>()
            old.fields.forEach {
                readersAsSerialized[it.name] = try {
                    OldParam(-1, PropertySerializer.make(it.name, EvolutionPropertyReader(),
                            it.getTypeAsClass(factory.classloader), factory))
                } catch (e: ClassNotFoundException) {
                    throw AMQPNotSerializableException(new.type, e.message ?: "")
                }
            }

            // cope with the situation where a generic interface was serialised as a type, in such cases
            // return the synthesised object which is, given the absence of a constructor, a no op
            val constructor = getEvolverConstructor(new.type, readersAsSerialized) ?: return new

            val classProperties = new.type.asClass().propertyDescriptors()
            val type = new.type
            val typeInfo = TypeInformation.forType(type, factory)
            val objectConstructor = ObjectConstructor(type, constructor)
            return if (classProperties.isNotEmpty() && constructor.parameters.isEmpty()) {
                makeWithSetters(typeInfo, factory, objectConstructor, readersAsSerialized, classProperties)
            } else {
                makeWithConstructor(typeInfo, objectConstructor, readersAsSerialized)
            }
        }

        /**
         * We must evolve if the number of fields is different, or their names or types do not match up.
         */
        private fun mustEvolve(old: CompositeType, new: ObjectSerializer): Boolean {
            if (old.fields.size != new.propertyAccessors.size) return true
            old.fields.zip(new.propertyAccessors).forEach { (field, accessor) ->
                if (field.name != accessor.serializer.name) return true
                if (field.type != accessor.serializer.type) return true
            }
            return false
        }
    }

    override fun writeClassInfo(output: SerializationOutput) = Unit // nothing to do in this case

    override fun writeData(obj: Any, data: Data, output: SerializationOutput, context: SerializationContext, debugLevel: Int) =
            Unit // nothing to do in this case

    override val propertyAccessors get() = emptyList<PropertyAccessor>()

    @JvmDefault
    override fun writeObject(obj: Any, data: Data, type: Type, output: SerializationOutput,
                             context: SerializationContext, debugIndent: Int): Unit =
        throw UnsupportedOperationException("It should be impossible to write an evolution serializer")
}

private class EvolutionSerializerViaConstructor(
        typeInformation: TypeInformation,
        private val oldReaders: Map<String, EvolutionSerializer.OldParam>,
        private val constructorArgs: Array<Any?>,
        private val objectConstructor: ObjectConstructor):
        EvolutionSerializer,
        HasTypeInformation by typeInformation {

    /**
     * Unlike a normal [readObject] call where we simply apply the parameter deserialisers
     * to the object list of values we need to map that list, which is ordered per the
     * constructor of the original state of the object, we need to map the new parameter order
     * of the current constructor onto that list inserting nulls where new parameters are
     * encountered.
     *
     * TODO: Object references
     */
    override fun readObject(obj: Any, schemas: SerializationSchemas, input: DeserializationInput,
                            context: SerializationContext
    ): Any {
        if (obj !is List<*>) throw NotSerializableException("Body of described type is unexpected $obj")

        // *must* read all the parameters in the order they were serialized
        oldReaders.values.zip(obj).map { (reader, item) -> reader.readProperty(item, schemas, input, constructorArgs, context) }

        return objectConstructor.construct(constructorArgs)
    }
}

/**
 * Specific instance of an [EvolutionSerializer] where the properties of the object are set via calling
 * named setter functions on the instantiated object.
 */
private class EvolutionSerializerViaSetters(
        typeInformation: TypeInformation,
        private val oldReaders: Map<String, EvolutionSerializer.OldParam>,
        private val objectConstructor: ObjectConstructor,
        private val setters: Map<String, PropertyAccessor>):
        EvolutionSerializer,
        HasTypeInformation by typeInformation {

    override fun readObject(obj: Any, schemas: SerializationSchemas, input: DeserializationInput,
                            context: SerializationContext
    ): Any {
        if (obj !is List<*>) throw NotSerializableException("Body of described type is unexpected $obj")

        val instance: Any = objectConstructor.construct()

        // *must* read all the parameters in the order they were serialized
        oldReaders.values.zip(obj).forEach {
            // if that property still exists on the new object then set it
            it.first.property.readProperty(it.second, schemas, input, context).apply {
                setters[it.first.property.name]?.set(instance, this)
            }
        }
        return instance
    }
}

/**
 * Instances of this type are injected into a [SerializerFactory] at creation time to dictate the
 * behaviour of evolution within that factory. Under normal circumstances this will simply
 * be an object that returns an [EvolutionSerializer]. Of course, any implementation that
 * extends this class can be written to invoke whatever behaviour is desired.
 */
abstract class EvolutionSerializerGetterBase {
    abstract fun getEvolutionSerializer(
            factory: SerializerFactory,
            typeNotation: TypeNotation,
            newSerializer: AMQPSerializer<Any>,
            schemas: SerializationSchemas): AMQPSerializer<Any>
}

/**
 * The normal use case for generating an [EvolutionSerializer]'s based on the differences
 * between the received schema and the class as it exists now on the class path,
 */
@KeepForDJVM
class EvolutionSerializerGetter : EvolutionSerializerGetterBase() {
    override fun getEvolutionSerializer(factory: SerializerFactory,
                                        typeNotation: TypeNotation,
                                        newSerializer: AMQPSerializer<Any>,
                                        schemas: SerializationSchemas): AMQPSerializer<Any> {
        return factory.serializersByDescriptor.computeIfAbsent(typeNotation.descriptor.name!!) {
            when (typeNotation) {
                is CompositeType -> EvolutionSerializer.make(typeNotation, newSerializer as ObjectSerializer, factory)
                is RestrictedType -> {
                    // The fingerprint of a generic collection can be changed through bug fixes to the
                    // fingerprinting function making it appear as if the class has altered whereas it hasn't.
                    // Given we don't support the evolution of these generic containers, if it appears
                    // one has been changed, simply return the original serializer and associate it with
                    // both the new and old fingerprint
                    if (newSerializer is CollectionSerializer || newSerializer is MapSerializer) {
                        newSerializer
                    } else if (newSerializer is EnumSerializer){
                        EnumEvolutionSerializer.make(typeNotation, newSerializer, factory, schemas)
                    }
                    else {
                        loggerFor<SerializerFactory>().error("typeNotation=${typeNotation.name} Need to evolve unsupported type")
                        throw NotSerializableException ("${typeNotation.name} cannot be evolved")
                    }
                }
            }
        }
    }
}

