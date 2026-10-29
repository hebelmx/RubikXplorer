package dev.rubikxplorer.buildlogic

import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * The cube package carries the strongest coverage (≥ 90% lines) on top of the module-wide 80% rule.
 * Kover 0.9 rules take no filters, so a custom `cube` variant (the `jvm` sources and tests, filtered
 * to the cube package) gets its own rule, and `check` runs its `koverVerifyCube` task.
 */
internal object CubeCoverageGate {
    const val MODULE_PATH = ":core:domain"
    const val PACKAGE = "dev.rubikxplorer.domain.cube"
    const val VARIANT = "cube"
    const val MIN_LINE_COVERAGE_PERCENT = 90
}

internal fun Project.applyCubeCoverageGate() {
    extensions.configure<KoverProjectExtension> {
        currentProject {
            createVariant(CubeCoverageGate.VARIANT) { add("jvm") }
        }
        reports {
            variant(CubeCoverageGate.VARIANT) {
                filters { includes { packages(CubeCoverageGate.PACKAGE) } }
                verify {
                    rule("${CubeCoverageGate.PACKAGE} line coverage ≥ ${CubeCoverageGate.MIN_LINE_COVERAGE_PERCENT}%") {
                        minBound(CubeCoverageGate.MIN_LINE_COVERAGE_PERCENT)
                    }
                }
            }
        }
    }
    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
        dependsOn("koverVerify${CubeCoverageGate.VARIANT.replaceFirstChar { it.uppercase() }}")
    }
}
