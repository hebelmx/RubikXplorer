package dev.cubentia.domain.content

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Reads content files by path relative to the content root; null when the file does not exist. */
fun interface ContentSource {
    fun read(path: String): ByteArray?

    companion object {
        /** The bundle shipped in `:core:domain` resources under `content/`. */
        val SHIPPED: ContentSource = classpath(ContentLoader.ROOT)

        /** Files under classpath resource directory [root] (for example `content/`). */
        fun classpath(root: String): ContentSource {
            val base = root.trimEnd('/')
            return ContentSource { path ->
                ContentLoader::class.java.classLoader
                    .getResourceAsStream("$base/$path")
                    ?.use { it.readBytes() }
            }
        }
    }
}

/** The outcome of [ContentLoader.load]. */
sealed interface ContentLoadResult {
    data class Loaded(
        val bundle: ContentBundle,
    ) : ContentLoadResult

    data class Failed(
        val error: ContentLoadError,
    ) : ContentLoadResult
}

/** Why content did not load. */
sealed interface ContentLoadError {
    /** The source has no file at [path]. */
    data class MissingResource(
        val path: String,
    ) : ContentLoadError

    /** The file at [path] is not valid JSON of the expected shape (missing field, unknown key or predicate). */
    data class Malformed(
        val path: String,
        val detail: String,
    ) : ContentLoadError

    /** The manifest declares schema version [found]; this build reads only [supported]. */
    data class UnsupportedSchemaVersion(
        val found: Int,
        val supported: Int,
    ) : ContentLoadError

    /** The content decoded but breaks the rules of [ContentValidator]. */
    data class Invalid(
        val problems: List<ContentProblem>,
    ) : ContentLoadError
}

/**
 * Decodes and validates a content bundle (AD-6): the manifest, each Stage it lists in order,
 * `strings/en.json`, then `tutor-config.json`; every file read is part of the `contentRevision`.
 * Strict JSON: unknown keys, missing fields and unknown predicates are [ContentLoadError.Malformed].
 * Never throws.
 */
object ContentLoader {
    /** The classpath directory holding the shipped bundle. */
    const val ROOT = "content"

    /** The manifest path, relative to the content root. */
    const val MANIFEST = "bundle.json"

    /** The English strings path, relative to the content root. */
    const val STRINGS = "strings/en.json"

    /** The tutor configuration path, relative to the content root. */
    const val TUTOR_CONFIG = "tutor-config.json"

    /** The only `schemaVersion` this build reads. */
    const val SUPPORTED_SCHEMA_VERSION = 1

    private const val SCHEMA_VERSION_KEY = "schemaVersion"
    private const val PATH_SEPARATOR: Byte = 0

    /** The strict content [Json]: predicates are discriminated by their `"predicate"` member. */
    val json: Json =
        Json {
            classDiscriminator = "predicate"
            ignoreUnknownKeys = false
            coerceInputValues = false
            isLenient = false
        }

    fun load(source: ContentSource): ContentLoadResult =
        try {
            ContentLoadResult.Loaded(decode(source))
        } catch (failure: LoadFailure) {
            ContentLoadResult.Failed(failure.error)
        }

    private fun decode(source: ContentSource): ContentBundle {
        val digest = MessageDigest.getInstance("SHA-256")

        fun read(path: String): String {
            val bytes = readBytes(source, path)
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(PATH_SEPARATOR)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
            digest.update(bytes)
            return bytes.toString(Charsets.UTF_8)
        }

        val manifestText = read(MANIFEST)
        val version = parse(MANIFEST) { schemaVersionOf(manifestText) }
        if (version != SUPPORTED_SCHEMA_VERSION) {
            fail(ContentLoadError.UnsupportedSchemaVersion(version, SUPPORTED_SCHEMA_VERSION))
        }
        val manifest = parse(MANIFEST) { json.decodeFromString<BundleManifest>(manifestText) }
        val stages = manifest.stages.map { path -> read(path).let { parse(path) { json.decodeFromString<Stage>(it) } } }
        val strings = read(STRINGS).let { parse(STRINGS) { json.decodeFromString<Map<String, String>>(it) } }
        val tutorConfig = read(TUTOR_CONFIG).let { parse(TUTOR_CONFIG) { json.decodeFromString<TutorConfig>(it) } }

        val revision = digest.digest().joinToString("") { "%02x".format(it) }
        val bundle = ContentBundle(manifest.schemaVersion, revision, stages, strings, tutorConfig)
        val problems = parse(MANIFEST) { ContentValidator.validate(bundle) }
        if (problems.isNotEmpty()) fail(ContentLoadError.Invalid(problems))
        return bundle
    }

    private fun schemaVersionOf(text: String): Int {
        val manifest: JsonObject = json.parseToJsonElement(text).jsonObject
        val version = manifest[SCHEMA_VERSION_KEY] as? JsonPrimitive
        return version?.takeUnless { it.isString }?.intOrNull ?: throw SerializationException(SCHEMA_VERSION_KEY)
    }

    private fun readBytes(
        source: ContentSource,
        path: String,
    ): ByteArray {
        val bytes =
            try {
                source.read(path)
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                fail(ContentLoadError.Malformed(path, failure.toString()))
            }
        return bytes ?: fail(ContentLoadError.MissingResource(path))
    }

    private inline fun <T> parse(
        path: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (failure: LoadFailure) {
            throw failure
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            // Any decode or validation crash is a sealed failure; `load` never throws.
            fail(ContentLoadError.Malformed(path, failure.message ?: failure.toString()))
        }

    private fun fail(error: ContentLoadError): Nothing = throw LoadFailure(error)

    /** Internal control flow only; [load] turns it into [ContentLoadResult.Failed]. */
    private class LoadFailure(
        val error: ContentLoadError,
    ) : RuntimeException(null, null, false, false)
}
