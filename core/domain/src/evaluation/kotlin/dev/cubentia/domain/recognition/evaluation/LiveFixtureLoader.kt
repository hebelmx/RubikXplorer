package dev.cubentia.domain.recognition.evaluation

import dev.cubentia.domain.recognition.LiveFixtureJson
import dev.cubentia.domain.recognition.LiveFixturePaths
import java.io.File

object LiveFixtureLoader {
    /** Every directory is a group, even an empty one, so a capture with no saved face shows as missing faces. */
    fun load(root: File): List<FixtureGroup> {
        val groups =
            root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.map { directory ->
                val files =
                    directory
                        .listFiles()
                        .orEmpty()
                        .filter { it.isFile && it.extension == "json" }
                        .sortedBy { it.name }
                val records =
                    files.map { file ->
                        try {
                            LiveFixtureJson.decode(file.readText()).also {
                                val expectedName = LiveFixturePaths.fileName(it.labeledSample.sample.face)
                                require(file.name == expectedName) { "Face does not match filename" }
                                val expectedDirectory = LiveFixturePaths.directoryName(it.device.model, it.cube, it.light)
                                require(directory.name == expectedDirectory) {
                                    "Device, cube and light belong in $expectedDirectory, not ${directory.name}"
                                }
                            }
                        } catch (failure: Exception) {
                            throw IllegalArgumentException("Invalid fixture ${file.path}: ${failure.message}", failure)
                        }
                    }
                FixtureGroup(directory.name, records)
            }
        require(groups.any { it.records.isNotEmpty() }) { "No live fixtures in ${root.path}" }
        return groups
    }
}
