plugins {
    alias(libs.plugins.rubikxplorer.jvm.core)
}

// Story 1.10 NFR-3 harness: its own source set, so it never ships in the `main` jar and stays out
// of the Kover denominator. `build` compiles and tests it; only `solveCorpusBenchmark` runs it.
val benchmark: SourceSet by sourceSets.creating

dependencies {
    implementation(project(":core:application"))
    implementation(project(":core:domain"))

    "benchmarkImplementation"(project(":core:application"))
    "benchmarkImplementation"(project(":core:domain"))
    testImplementation(benchmark.output)
}

tasks.named("assemble") { dependsOn(benchmark.classesTaskName) }

tasks.register<JavaExec>("solveCorpusBenchmark") {
    group = "benchmark"
    description = "Runs the NFR-3 solve corpus through the JVM harness and prints the report (on demand only)."
    classpath = benchmark.runtimeClasspath
    mainClass.set("dev.rubikxplorer.solver.benchmark.SolveCorpusBenchmarkMainKt")
}
