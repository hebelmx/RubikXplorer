package dev.cubentia.solver.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class SolveCorpusBenchmarkMainTest {
    @Test
    fun `no arguments gives the defaults`() {
        assertEquals(ParsedArgs.Ok(BenchmarkOptions()), parseArgs(emptyList()))
        assertEquals(10_000, BenchmarkOptions().cases)
    }

    @Test
    fun `custom arguments are read`() {
        assertEquals(
            ParsedArgs.Ok(BenchmarkOptions(cases = 50, seed = 7, warmup = 0)),
            parseArgs(listOf("--cases", "50", "--seed", "7", "--warmup", "0")),
        )
    }

    @ParameterizedTest
    @CsvSource(
        "--cases, x, --cases",
        "--cases, 0, --cases",
        "--cases, -3, --cases",
        "--seed, 1.5, --seed",
        "--warmup, -1, --warmup",
        "--warmup, y, --warmup",
        "--iterations, 5, --iterations",
    )
    fun `invalid or unknown flag is rejected naming the flag`(
        flag: String,
        value: String,
        named: String,
    ) {
        val parsed = parseArgs(listOf(flag, value))
        assertTrue(parsed is ParsedArgs.Invalid, "$parsed")
        assertTrue(named in (parsed as ParsedArgs.Invalid).message)
    }

    @Test
    fun `flag without a value is rejected naming the flag`() {
        val parsed = parseArgs(listOf("--cases"))
        assertTrue(parsed is ParsedArgs.Invalid && "--cases" in parsed.message)
    }

    @Test
    fun `custom run reports over cases plus the solved state`() {
        val report = runBenchmark(BenchmarkOptions(cases = 50, seed = 7, warmup = 0))
        assertTrue("cases          151" in report, report)
        assertTrue("failures       0 (0.0%)" in report, report)
    }
}
