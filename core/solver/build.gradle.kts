plugins {
    alias(libs.plugins.cubentia.jvm.core)
}

// Story 1.10 NFR-3 harness: its own source set, so it never ships in the `main` jar and stays out
// of the Kover denominator. `build` compiles and tests it; only `solveCorpusBenchmark` runs it.
val benchmark: SourceSet by sourceSets.creating
benchmark.compileClasspath += sourceSets.main.get().output
benchmark.runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
// Story 3.8: the corpus code without `java.lang.management`, shared with the app's debug-only device runner.
kotlin.sourceSets.named("benchmark") { kotlin.srcDir("src/benchmarkShared/kotlin") }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":core:application"))
    implementation(project(":core:domain"))

    "benchmarkImplementation"(libs.kotlinx.coroutines.core)
    "benchmarkImplementation"(project(":core:application"))
    "benchmarkImplementation"(project(":core:domain"))
    testImplementation(benchmark.output)
}

tasks.named("assemble") { dependsOn(benchmark.classesTaskName) }

tasks.register<JavaExec>("solveCorpusBenchmark") {
    group = "benchmark"
    description = "Runs the NFR-3 solve corpus through the JVM harness and prints the report (on demand only)."
    classpath = benchmark.runtimeClasspath
    mainClass.set("dev.cubentia.solver.benchmark.SolveCorpusBenchmarkMainKt")
}

// Story 3.8 temporary bundled candidate, measurement only: the provider-format table file as an asset dir.
val pruningTableAssetDir = layout.buildDirectory.dir("generated/pruningTableAsset")
tasks.register<JavaExec>("writePruningTableAsset") {
    group = "benchmark"
    description = "Writes solver/pruning-v1.bin (provider format) for the temporary bundled-table candidate."
    classpath = benchmark.runtimeClasspath
    mainClass.set("dev.cubentia.solver.benchmark.PruningTableAssetMainKt")
    outputs.dir(pruningTableAssetDir)
    argumentProviders +=
        CommandLineArgumentProvider {
            listOf(
                pruningTableAssetDir
                    .get()
                    .file("solver/pruning-v1.bin")
                    .asFile.path,
            )
        }
    doFirst {
        pruningTableAssetDir
            .get()
            .dir("solver")
            .asFile
            .mkdirs()
    }
}
