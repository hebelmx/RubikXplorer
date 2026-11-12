package dev.cubentia.buildlogic.policy

/** Validated, optional release inputs. No environment access or secret values in errors. */
object ReleaseInputs {
    val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").map { "cubentia.signing.$it" }

    fun signing(properties: Map<String, String>): Map<String, String>? {
        if (signingKeys.none { it in properties }) return null
        val missing = signingKeys.filter { properties[it].isNullOrBlank() }
        require(missing.isEmpty()) { "Incomplete signing inputs; missing: ${missing.joinToString()}" }
        return properties.filterKeys { it in signingKeys }
    }

    fun versionCode(value: String?): Int? =
        value?.let {
            requireNotNull(it.toIntOrNull()?.takeIf { code -> code in 1..2_100_000_000 }) {
                "cubentia.versionCode must be a positive integer <= 2100000000"
            }
        }

    fun versionName(value: String?): String? =
        value?.also {
            require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "cubentia.versionName must match X.Y.Z" }
        }
}
