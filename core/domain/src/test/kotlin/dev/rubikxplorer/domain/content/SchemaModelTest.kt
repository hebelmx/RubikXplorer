package dev.rubikxplorer.domain.content

import dev.rubikxplorer.domain.predicate.Predicate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AC 1: each `content/schema/<name>.schema.json` is the single source of the content shape and equals the Kotlin model. */
class SchemaModelTest {
    private fun shipped(file: String): JsonObject = Json.parseToJsonElement(resourceText("content/schema/$file")).jsonObject

    private val schemas: Map<String, JsonObject> =
        listOf("bundle", "stage", "lesson", "predicate", "tutor-config").associate { "$it.schema.json" to shipped("$it.schema.json") }

    @Test
    fun `every schema file is draft 2020-12 and matches its Kotlin class`() {
        val roots =
            mapOf(
                "bundle.schema.json" to BundleManifest.serializer().descriptor,
                "stage.schema.json" to Stage.serializer().descriptor,
                "lesson.schema.json" to Lesson.serializer().descriptor,
                "predicate.schema.json" to Predicate.serializer().descriptor,
                "tutor-config.schema.json" to TutorConfig.serializer().descriptor,
            )
        roots.keys.forEach { file ->
            assertEquals(DRAFT, schemas.getValue(file)["\$schema"]?.jsonPrimitive?.content, file)
        }
        roots.forEach { (file, descriptor) ->
            assertEquals(emptyList<String>(), SchemaComparator(schemas).compare(file, descriptor), file)
        }
    }

    @Test
    fun `a tutor config schema with a field the model lacks, or open to unknown keys, is a mismatch`() {
        val config = schemas.getValue("tutor-config.schema.json")
        val broken =
            JsonObject(
                config +
                    mapOf(
                        "properties" to
                            JsonObject(
                                config.getValue("properties").jsonObject +
                                    ("hintThreshold" to JsonObject(mapOf("type" to JsonPrimitive("integer")))),
                            ),
                        "additionalProperties" to JsonPrimitive(true),
                    ),
            )
        val problems =
            SchemaComparator(
                schemas + ("tutor-config.schema.json" to broken),
            ).compare("tutor-config.schema.json", TutorConfig.serializer().descriptor)
        assertTrue(problems.any { "hintThreshold" in it }, problems.toString())
        assertTrue(problems.any { "additionalProperties" in it }, problems.toString())
    }

    @Test
    fun `a missing required Lesson field in the schema is a mismatch`() {
        val lesson = schemas.getValue("lesson.schema.json")
        val properties = lesson.getValue("properties").jsonObject
        val required = lesson.getValue("required").jsonArray
        val broken =
            JsonObject(
                lesson +
                    mapOf(
                        "properties" to JsonObject(properties - "recovery"),
                        "required" to JsonArray(required.filterNot { it.jsonPrimitive.content == "recovery" }),
                    ),
            )
        val problems =
            SchemaComparator(
                schemas + ("lesson.schema.json" to broken),
            ).compare("lesson.schema.json", Lesson.serializer().descriptor)
        assertTrue(problems.any { "recovery" in it }, problems.toString())
    }

    @Test
    fun `a wrong JSON type, an optional field, open objects and an unknown predicate are mismatches`() {
        val lesson = schemas.getValue("lesson.schema.json")
        val properties = lesson.getValue("properties").jsonObject
        val wrongType =
            JsonObject(
                lesson +
                    mapOf(
                        "properties" to JsonObject(properties + ("entryState" to JsonObject(mapOf("type" to JsonPrimitive("integer"))))),
                        "required" to JsonArray(lesson.getValue("required").jsonArray.drop(1)),
                        "additionalProperties" to JsonPrimitive(true),
                    ),
            )
        val lessonProblems =
            SchemaComparator(schemas + ("lesson.schema.json" to wrongType)).compare("lesson.schema.json", Lesson.serializer().descriptor)
        assertTrue(lessonProblems.any { "entryState" in it }, lessonProblems.toString())
        assertTrue(lessonProblems.any { "required" in it }, lessonProblems.toString())
        assertTrue(lessonProblems.any { "additionalProperties" in it }, lessonProblems.toString())

        val unknown =
            Json
                .parseToJsonElement(
                    """{"oneOf":[{"type":"object","additionalProperties":false,"required":["predicate"],""" +
                        """"properties":{"predicate":{"const":"nope"}}}]}""",
                ).jsonObject
        val predicateProblems =
            SchemaComparator(
                schemas + ("predicate.schema.json" to unknown),
            ).compare("predicate.schema.json", Predicate.serializer().descriptor)
        assertTrue(predicateProblems.any { "nope" in it }, predicateProblems.toString())
    }

    @Test
    fun `a combinator whose items do not refer back to the predicate root is a mismatch`() {
        val predicate = schemas.getValue("predicate.schema.json")
        val options =
            predicate.getValue("oneOf").jsonArray.map { option ->
                val properties = option.jsonObject.getValue("properties").jsonObject
                val isAllOf =
                    properties
                        .getValue("predicate")
                        .jsonObject["const"]
                        ?.jsonPrimitive
                        ?.content == "allOf"
                if (!isAllOf) {
                    option
                } else {
                    val predicates = properties.getValue("predicates").jsonObject
                    val brokenItems = JsonObject(mapOf("\$ref" to JsonPrimitive("#/oneOf/0")))
                    JsonObject(
                        option.jsonObject +
                            ("properties" to JsonObject(properties + ("predicates" to JsonObject(predicates + ("items" to brokenItems))))),
                    )
                }
            }
        val broken = JsonObject(predicate + ("oneOf" to JsonArray(options)))
        val problems =
            SchemaComparator(
                schemas + ("predicate.schema.json" to broken),
            ).compare("predicate.schema.json", Predicate.serializer().descriptor)
        assertTrue(problems.any { "allOf" in it && "root schema" in it }, problems.toString())
    }

    private companion object {
        const val DRAFT = "https://json-schema.org/draft/2020-12/schema"
    }
}

/**
 * Compares a JSON Schema (following `$ref`s across [schemas]) with a [SerialDescriptor]: property
 * names, the `required` set, `additionalProperties: false` and JSON types must agree.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class SchemaComparator(
    private val schemas: Map<String, JsonObject>,
) {
    private val problems = mutableListOf<String>()

    /**
     * Sealed descriptors on the current path, with the file and schema each is walked against.
     * Re-entering one (a combinator's `$ref "#"`) stops the walk, and must land on that same schema.
     */
    private val sealedOnPath = mutableMapOf<String, Pair<String, JsonObject>>()

    fun compare(
        file: String,
        descriptor: SerialDescriptor,
    ): List<String> {
        problems.clear()
        sealedOnPath.clear()
        node(file, schemas.getValue(file), descriptor, file)
        return problems.toList()
    }

    /** [schema] as found in [file], with `$ref` resolved; returns the schema and the file it lives in. */
    private fun resolve(
        file: String,
        schema: JsonObject,
    ): Pair<String, JsonObject> {
        val ref = schema["\$ref"]?.jsonPrimitive?.content ?: return file to schema
        val target = ref.substringBefore('#').ifEmpty { file }
        var resolved: JsonElement = schemas[target] ?: return target to JsonObject(emptyMap()).also { problems += "unresolved \$ref $ref" }
        ref
            .substringAfter('#', "")
            .split('/')
            .filter { it.isNotEmpty() }
            .forEach { resolved = (resolved as? JsonArray)?.get(it.toInt()) ?: resolved.jsonObject.getValue(it) }
        return resolve(target, resolved.jsonObject)
    }

    private fun node(
        file: String,
        raw: JsonObject,
        descriptor: SerialDescriptor,
        path: String,
    ) {
        val (home, schema) = resolve(file, raw)
        val actual = if (descriptor.isInline) descriptor.getElementDescriptor(0) else descriptor
        val type = schema["type"]?.jsonPrimitive?.contentOrNull
        when (actual.kind) {
            PrimitiveKind.STRING -> {
                expectType(path, type, "string")
            }

            PrimitiveKind.INT -> {
                expectType(path, type, "integer")
            }

            StructureKind.LIST -> {
                expectType(path, type, "array")
                node(home, schema.getValue("items").jsonObject, actual.getElementDescriptor(0), "$path[]")
            }

            StructureKind.CLASS, StructureKind.OBJECT -> {
                objectNode(home, schema, actual, path, discriminator = null)
            }

            PolymorphicKind.SEALED -> {
                sealedNode(home, schema, actual, path)
            }

            else -> {
                problems += "$path: no schema mapping for ${actual.kind}"
            }
        }
    }

    private fun expectType(
        path: String,
        found: String?,
        expected: String,
    ) {
        if (found != expected) problems += "$path: schema type $found, model needs $expected"
    }

    private fun objectNode(
        file: String,
        schema: JsonObject,
        descriptor: SerialDescriptor,
        path: String,
        discriminator: String?,
    ) {
        expectType(path, schema["type"]?.jsonPrimitive?.contentOrNull, "object")
        if (schema["additionalProperties"]?.jsonPrimitive?.boolean != false) {
            problems += "$path: additionalProperties must be false"
        }
        val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val required =
            schema["required"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.toSet()
                .orEmpty()
        val modelNames = descriptor.elementNames.toSet()
        val modelRequired = descriptor.elementNames.filterIndexed { i, _ -> !descriptor.isElementOptional(i) }.toSet()
        val schemaNames = properties.keys - setOfNotNull(discriminator)
        if (schemaNames != modelNames) problems += "$path: schema properties ${schemaNames.sorted()}, model ${modelNames.sorted()}"
        val schemaRequired = required - setOfNotNull(discriminator)
        if (schemaRequired !=
            modelRequired
        ) {
            problems += "$path: schema required ${schemaRequired.sorted()}, model ${modelRequired.sorted()}"
        }
        if (discriminator != null && discriminator !in required) problems += "$path: $discriminator must be required"
        descriptor.elementNames.forEachIndexed { i, name ->
            properties[name]?.let { node(file, it.jsonObject, descriptor.getElementDescriptor(i), "$path.$name") }
        }
    }

    private fun sealedNode(
        file: String,
        schema: JsonObject,
        descriptor: SerialDescriptor,
        path: String,
    ) {
        val walking = sealedOnPath[descriptor.serialName]
        if (walking != null) {
            if (walking != (file to schema)) problems += "$path: recursive ${descriptor.serialName} must refer to its own root schema"
            return
        }
        sealedOnPath[descriptor.serialName] = file to schema
        try {
            sealedVariants(file, schema, descriptor, path)
        } finally {
            sealedOnPath.remove(descriptor.serialName)
        }
    }

    private fun sealedVariants(
        file: String,
        schema: JsonObject,
        descriptor: SerialDescriptor,
        path: String,
    ) {
        val variants = descriptor.getElementDescriptor(1)
        val model = (0 until variants.elementsCount).associate { variants.getElementName(it) to variants.getElementDescriptor(it) }
        val options = schema["oneOf"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val bySchemaName =
            options.associateBy { option ->
                option["properties"]
                    ?.jsonObject
                    ?.get(DISCRIMINATOR)
                    ?.jsonObject
                    ?.get("const")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            }
        if (bySchemaName.keys !=
            model.keys
        ) {
            problems += "$path: schema predicates ${bySchemaName.keys.sorted()}, model ${model.keys.sorted()}"
        }
        bySchemaName.forEach { (name, option) ->
            model[name]?.let { objectNode(file, option, it, "$path<$name>", DISCRIMINATOR) }
        }
    }

    private companion object {
        const val DISCRIMINATOR = "predicate"
    }
}
