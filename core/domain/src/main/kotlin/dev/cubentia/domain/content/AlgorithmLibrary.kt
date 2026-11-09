package dev.cubentia.domain.content

/** Learner-owned status; absent entries are Not started. */
enum class AlgorithmStatus { NOT_STARTED, LEARNING, LEARNED }

data class LibraryFilter(
    val family: FamilyId? = null,
    val status: AlgorithmStatus? = null,
)

data class LibraryEntry(
    val case: Case,
    val algorithm: Algorithm,
)

data class LibrarySection(
    val set: CaseSet,
    val family: FamilyId,
    val entries: List<LibraryEntry>,
)

/** Pure Library projection, preserving content order within each family. */
object AlgorithmLibrary {
    fun families(cases: List<Case>): List<FamilyId> = cases.sortedBy { it.set.ordinal }.map { it.family }.distinct()

    fun sections(
        cases: List<Case>,
        algorithms: List<Algorithm>,
        filter: LibraryFilter = LibraryFilter(),
        statuses: Map<AlgorithmId, AlgorithmStatus> = emptyMap(),
    ): List<LibrarySection> {
        val primary = algorithms.filter { it.isPrimary }.associateBy { it.caseId }
        return cases
            .sortedBy { it.set.ordinal }
            .mapNotNull { case -> primary[case.id]?.let { LibraryEntry(case, it) } }
            .filter { filter.family == null || it.case.family == filter.family }
            .filter { filter.status == null || (statuses[it.algorithm.id] ?: AlgorithmStatus.NOT_STARTED) == filter.status }
            .groupBy { it.case.set to it.case.family }
            .map { (key, entries) -> LibrarySection(key.first, key.second, entries) }
    }
}
