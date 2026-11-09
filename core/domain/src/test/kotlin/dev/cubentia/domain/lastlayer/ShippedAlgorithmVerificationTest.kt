package dev.cubentia.domain.lastlayer

import org.junit.jupiter.api.Test

/** Story 9.2 AC 1, FR-18, NFR-4: a wrong Algorithm in the shipped bundle fails `./gradlew build`. */
class ShippedAlgorithmVerificationTest {
    @Test
    fun `every shipped Algorithm matches only its own Case and solves the last layer up to AUF`() {
        AlgorithmVerification.assertVerified(AlgorithmVerification.shippedBundle())
    }
}
