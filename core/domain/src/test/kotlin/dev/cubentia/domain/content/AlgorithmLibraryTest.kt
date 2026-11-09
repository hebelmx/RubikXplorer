package dev.cubentia.domain.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlgorithmLibraryTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle

    private fun sections(
        filter: LibraryFilter = LibraryFilter(),
        statuses: Map<AlgorithmId, AlgorithmStatus> = emptyMap(),
    ) = AlgorithmLibrary.sections(bundle.cases, bundle.algorithms, filter, statuses)

    @Test fun `all primary Algorithms appear once in family and content order`() {
        val sections = sections()
        assertEquals(78, sections.sumOf { it.entries.size })
        assertEquals(
            78,
            sections
                .flatMap { it.entries }
                .map { it.case.id }
                .distinct()
                .size,
        )
        assertEquals(18, sections.size)
        assertEquals(AlgorithmLibrary.families(bundle.cases), sections.map { it.family })
        assertEquals(sections.map { it.set }.sortedBy { it.ordinal }, sections.map { it.set })
        sections.forEach { section ->
            assertEquals(bundle.cases.filter { it.family == section.family }, section.entries.map { it.case })
            assertTrue(section.entries.all { it.algorithm.isPrimary && it.algorithm.caseId == it.case.id })
        }
    }

    @Test fun `family status and combined filters use primary status with Not started default`() {
        val family = FamilyId("oll-dot")
        assertEquals(8, sections(LibraryFilter(family)).single().entries.size)
        assertEquals(78, sections(LibraryFilter(status = AlgorithmStatus.NOT_STARTED)).sumOf { it.entries.size })
        val algorithm = bundle.algorithms.first { it.isPrimary }
        val statuses = mapOf(algorithm.id to AlgorithmStatus.LEARNING)
        assertEquals(
            listOf(algorithm),
            sections(LibraryFilter(status = AlgorithmStatus.LEARNING), statuses)
                .flatMap {
                    it.entries
                }.map { it.algorithm },
        )
        assertEquals(1, sections(LibraryFilter(family, AlgorithmStatus.LEARNING), statuses).single().entries.size)
        assertTrue(sections(LibraryFilter(FamilyId("pll-edges-only"), AlgorithmStatus.LEARNING), statuses).isEmpty())
        assertTrue(sections(LibraryFilter(status = AlgorithmStatus.LEARNING)).isEmpty())
        assertTrue(sections(LibraryFilter(status = AlgorithmStatus.LEARNED)).isEmpty())
        assertEquals(
            1,
            sections(LibraryFilter(status = AlgorithmStatus.LEARNED), mapOf(algorithm.id to AlgorithmStatus.LEARNED)).single().entries.size,
        )
    }

    @Test fun `missing primary is omitted and set order overrides input order`() {
        val oll = bundle.cases.first()
        val pll = bundle.cases.last()
        val cases = listOf(pll, oll)
        assertEquals(listOf(oll.family, pll.family), AlgorithmLibrary.families(cases))
        assertEquals(listOf(oll, pll), AlgorithmLibrary.sections(cases, bundle.algorithms).flatMap { it.entries }.map { it.case })
        assertTrue(AlgorithmLibrary.sections(listOf(oll), bundle.algorithms.filterNot { it.caseId == oll.id && it.isPrimary }).isEmpty())
    }

    @Test fun `interleaved fixture joins only primary and ignores alternate status`() {
        val first = Case(CaseId("case-oll-01"), CaseSet.OLL, FamilyId("oll-dot"), TextKey("case.case-oll-01.name"))
        val second = first.copy(id = CaseId("case-oll-02"), family = FamilyId("oll-line"))
        val third = first.copy(id = CaseId("case-oll-03"))
        val primary = Algorithm(AlgorithmId("alg-oll-01-a"), first.id, "R U")
        val alternate = primary.copy(id = AlgorithmId("alg-oll-01-b"))
        val algorithms =
            listOf(
                alternate,
                primary,
                primary.copy(id = AlgorithmId("alg-oll-02-a"), caseId = second.id),
                primary.copy(id = AlgorithmId("alg-oll-03-a"), caseId = third.id),
            )
        val cases = listOf(first, second, third)
        val sections = AlgorithmLibrary.sections(cases, algorithms)
        assertEquals(listOf(first, third), sections.first().entries.map { it.case })
        assertEquals(listOf(second), sections.last().entries.map { it.case })
        assertEquals(
            primary,
            sections
                .first()
                .entries
                .first()
                .algorithm,
        )
        assertTrue(
            AlgorithmLibrary
                .sections(
                    cases,
                    algorithms,
                    LibraryFilter(status = AlgorithmStatus.LEARNING),
                    mapOf(alternate.id to AlgorithmStatus.LEARNING),
                ).isEmpty(),
        )
    }
}
