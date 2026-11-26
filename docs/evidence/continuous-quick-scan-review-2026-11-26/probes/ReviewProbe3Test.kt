package dev.cubentia.domain.recognition.continuous
import org.junit.jupiter.api.Test
import java.io.File
class ReviewProbe3Test {
    @Test fun faceSize() {
        val frames = SyntheticCubeStream.generateSequence(SyntheticCubeStream.buildScenarios()[0])
        val edges = frames.flatMap { f -> f.visibleFaces.values.map { ProjectiveGeometry.quadQuality(it).second } }.sorted()
        File("/tmp/claude-1000/-mnt-e-Dynamic-IndFun-RubikXplorer/5fa36894-35c8-4f0d-a8c1-e6ebd0c5af03/scratchpad/probe-out3.txt").writeText("edge p10=${edges[edges.size/10]} p50=${edges[edges.size/2]} p90=${edges[edges.size*9/10]}\n")
    }
}
