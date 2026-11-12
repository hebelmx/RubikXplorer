package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReleaseInputsTest {
    @Test
    fun `signing is optional but must be complete`() {
        assertNull(ReleaseInputs.signing(emptyMap()))
        val complete = ReleaseInputs.signingKeys.associateWith { "secret" }
        assertEquals(complete, ReleaseInputs.signing(complete))
        ReleaseInputs.signingKeys.forEach { key ->
            val error = assertThrows<IllegalArgumentException> { ReleaseInputs.signing(complete - key) }
            assertTrue(error.message!!.contains(key))
            assertFalse(error.message!!.contains("secret"))
            assertThrows<IllegalArgumentException> { ReleaseInputs.signing(complete + (key to "")) }
        }
    }

    @Test
    fun `version code respects Play bounds`() {
        assertNull(ReleaseInputs.versionCode(null))
        assertEquals(42, ReleaseInputs.versionCode("42"))
        assertEquals(2_100_000_000, ReleaseInputs.versionCode("2100000000"))
        listOf("0", "-1", "2100000001", "999999999999", "abc", "").forEach {
            assertThrows<IllegalArgumentException> { ReleaseInputs.versionCode(it) }
        }
    }

    @Test
    fun `version name is three numeric components`() {
        assertNull(ReleaseInputs.versionName(null))
        assertEquals("1.2.3", ReleaseInputs.versionName("1.2.3"))
        listOf("v1.2.3", "1.2", "1.2.3-beta", "", "1a2b3").forEach {
            assertThrows<IllegalArgumentException> { ReleaseInputs.versionName(it) }
        }
    }
}
