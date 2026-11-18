package dev.cubentia.domain.recognition.evaluation.matrix

import dev.cubentia.domain.content.CaseId
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.recognition.ScanTrace
import java.io.File

class MatrixTruth private constructor(
    private val values: Map<Pair<MatrixCell, Int?>, String>,
) {
    operator fun get(run: MatrixRun): String? = values[run.cell to run.index] ?: values[run.cell to null]

    companion object {
        fun load(file: File): MatrixTruth {
            // The default empty matrix needs no truth file. A present file is always validated.
            if (!file.exists()) return MatrixTruth(emptyMap())
            val values = mutableMapOf<Pair<MatrixCell, Int?>, String>()
            file.useLines { lines ->
                var first = true
                lines.forEachIndexed { index, raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
                    val columns = line.split(',').map(String::trim)
                    val header = first && columns == listOf("cell", "run", "truth")
                    first = false
                    if (header) return@forEachIndexed

                    fun invalid(reason: String): Nothing = throw IllegalArgumentException("${file.path}:${index + 1}: $reason")

                    if (columns.size != 3) invalid("expected cell,run,truth")
                    val (name, number, token) = columns
                    val cell = MatrixCell.parse(name) ?: invalid("invalid cell '$name'")
                    val run = if (number == "*") null else number.toIntOrNull()?.takeIf { it > 0 } ?: invalid("invalid run '$number'")
                    val truth =
                        when (cell.feature) {
                            MatrixFeature.GUIDED, MatrixFeature.QUICK_SCAN -> {
                                val moves =
                                    when (val parsed = Notation.parse(token)) {
                                        is NotationResult.Parsed -> parsed.moves
                                        is NotationResult.Invalid -> invalid("invalid scramble: ${parsed.error}")
                                    }
                                CubeState.SOLVED
                                    .apply(moves)
                                    .reoriented(Orientation.HOME)
                                    .toFacelets()
                                    .colors
                                    .joinToString("", transform = ScanTrace::letter)
                            }

                            MatrixFeature.QUICK_CHECK -> {
                                token.takeIf { it == "match" || it == "mismatch" }
                            }

                            MatrixFeature.RECOGNIZE -> {
                                token.takeIf { CaseId(it).isWellFormed || it == "LL_SOLVED" || it == "NOT_AT_LL" }
                            }

                            MatrixFeature.PLACEMENT -> {
                                token.takeIf { StageId(it).isWellFormed || it == "SOLVED" }
                            }
                        } ?: invalid("unknown truth '$token' for ${cell.feature.token}")
                    val key = cell to run
                    if (key in values) invalid("duplicate truth for '$name' run '$number'")
                    values[key] = truth
                }
            }
            return MatrixTruth(values)
        }
    }
}
