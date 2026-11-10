package dev.cubentia.app.ui.shell

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.lastlayer.RecognitionDiagram
import dev.cubentia.domain.lastlayer.RecognitionDiagramResult
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionDiagramChoiceTest {
    @Test fun firstOllAndPllUseTheirLibraryDiagram() {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        listOf("alg-oll-02-a", "alg-pll-t-a").forEach { id ->
            val algorithm = bundle.algorithms.single { it.id.value == id }
            val lesson = bundle.twoLookStages.flatMap { it.lessons }.first { algorithm.id in bundle.lessonAlgorithms.getValue(it.id) }
            assertEquals(
                (RecognitionDiagram.derive(algorithm) as RecognitionDiagramResult.Derived).diagram,
                recognitionDiagramFor(bundle, lesson.id),
            )
        }
    }
}
