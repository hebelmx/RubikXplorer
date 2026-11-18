package dev.cubentia.domain.recognition.evaluation.matrix

import java.io.File

enum class MatrixFeature(
    val token: String,
    val tag: String,
) {
    GUIDED("guided", "guidedScan"),
    QUICK_SCAN("quickscan", "quickScan"),
    QUICK_CHECK("quickcheck", "quickCheck"),
    RECOGNIZE("recognize", "recognize"),
    PLACEMENT("placement", "placement"),
}

data class MatrixCell(
    val device: String,
    val cube: String,
    val light: String,
    val feature: MatrixFeature,
) {
    val name get() = "${device}_${cube}_${light}_${feature.token}"

    companion object {
        private val namePattern = Regex("([A-Za-z0-9-]+)_([A-Za-z0-9-]+)_(daylight|warm|cool|low)_([a-z]+)")

        fun parse(name: String): MatrixCell? {
            val (device, cube, light, feature) = namePattern.matchEntire(name)?.destructured ?: return null
            val kind = MatrixFeature.entries.firstOrNull { it.token == feature } ?: return null
            return MatrixCell(device.lowercase(), cube, light, kind)
        }
    }
}

data class MatrixRun(
    val cell: MatrixCell,
    val index: Int,
    val fields: Map<String, String>,
    val malformedLine: Int? = null,
) {
    operator fun get(key: String): String? = fields[key]?.takeUnless { it == "-" }

    fun number(key: String): Long? = get(key)?.toLongOrNull()?.takeIf { it >= 0 }
}

data class MatrixRuns(
    val cells: Map<MatrixCell, List<MatrixRun>>,
    val skipped: List<String>,
    val malformed: List<String> = emptyList(),
) {
    companion object {
        private val field = Regex("[A-Za-z][A-Za-z0-9]*=[^\\s=]+")

        fun parse(
            cell: MatrixCell,
            lines: Sequence<String>,
        ): List<MatrixRun> {
            val marker = "${cell.feature.tag} run "
            return lines
                .mapIndexedNotNull { lineIndex, line ->
                    // Accept raw messages and logcat prefixes, with the tag at a word boundary.
                    val offset = line.indexOf(marker)
                    if (offset < 0 || (offset > 0 && !line[offset - 1].isWhitespace())) return@mapIndexedNotNull null
                    val tokens = line.substring(offset + marker.length).trimEnd().split(' ')
                    val fields = tokens.associate { it.substringBefore('=') to it.substringAfter('=') }
                    if (tokens.any { !field.matches(it) } || fields.size != tokens.size) {
                        MatrixRun(cell, 0, emptyMap(), lineIndex + 1)
                    } else {
                        MatrixRun(cell, 0, fields)
                    }
                }.mapIndexed { i, run -> run.copy(index = i + 1) }
                .toList()
        }

        fun load(directory: File): MatrixRuns {
            val cells = linkedMapOf<MatrixCell, List<MatrixRun>>()
            val skipped = mutableListOf<String>()
            val malformed = mutableListOf<String>()
            directory.listFiles().orEmpty().filter { it.isFile && it.extension == "log" }.sortedBy { it.name }.forEach { file ->
                val cell = MatrixCell.parse(file.nameWithoutExtension)
                if (cell == null || cell in cells) {
                    skipped += file.name
                } else {
                    val runs = file.useLines { parse(cell, it) }
                    cells[cell] = runs
                    runs.mapNotNullTo(malformed) { it.malformedLine?.let { line -> "${file.name}:$line" } }
                }
            }
            return MatrixRuns(cells, skipped, malformed)
        }
    }
}
