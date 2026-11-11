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

    @Test
    fun `time scale is read as the calibrated profile factor`() {
        assertEquals(1.0, BenchmarkOptions().timeScale)
        assertEquals(ParsedArgs.Ok(BenchmarkOptions(timeScale = 7.25)), parseArgs(listOf("--time-scale", "7.25")))
    }

    @Test
    fun `calibrated run names its profile and factor`() {
        val report = runBenchmark(BenchmarkOptions(cases = 20, seed = 7, warmup = 0, timeScale = 3.5))
        assertTrue("calibrated reference-device-equivalent profile: host time x 3.5" in report, report)
        assertTrue("failures       0 (0.0%)" in report, report)
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
        "--time-scale, 0, --time-scale",
        "--time-scale, -2, --time-scale",
        "--time-scale, NaN, --time-scale",
        "--time-scale, Infinity, --time-scale",
        "--time-scale, z, --time-scale",
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
        assertTrue("uncalibrated JVM host" in report, report)
    }
}
