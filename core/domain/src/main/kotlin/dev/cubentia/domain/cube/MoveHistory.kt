package dev.cubentia.domain.cube

/**
 * An undo/redo history of Moves (FR-3): an immutable value starting from [start] ([CubeState.SOLVED]
 * for the free cube, a Lesson's entry state in a Learning Session). [commit] pushes a Move and clears
 * redo; [undo] and [redo] are no-ops when there is nothing to undo or redo; [reset] returns to
 * [start] with both stacks empty. The history itself is never persisted.
 */
class MoveHistory private constructor(
    /** Where the history starts and where [reset] returns. */
    val start: CubeState,
    /** The Cube State after every committed, not-undone Move. */
    val state: CubeState,
    /** The committed, not-undone Moves, oldest first. */
    val done: List<Move>,
    /** The undone Moves; [redo] applies the last one. */
    val undone: List<Move>,
) {
    /** An empty history starting from solved (the free cube). */
    constructor() : this(CubeState.SOLVED)

    /** An empty history starting from [start]. */
    constructor(start: CubeState) : this(start, start, emptyList(), emptyList())

    val canUndo: Boolean get() = done.isNotEmpty()

    val canRedo: Boolean get() = undone.isNotEmpty()

    /** The Move [undo] would take back, or `null`. */
    val undoable: Move? get() = done.lastOrNull()

    /** The Move [redo] would apply again, or `null`. */
    val redoable: Move? get() = undone.lastOrNull()

    /** Applies [move] and clears redo. */
    fun commit(move: Move): MoveHistory = MoveHistory(start, state.apply(move), done + move, emptyList())

    /** Takes back the last committed Move; unchanged when there is none. */
    fun undo(): MoveHistory {
        val move = undoable ?: return this
        return MoveHistory(start, state.apply(move.inverse()), done.dropLast(1), undone + move)
    }

    /** Applies the last undone Move again; unchanged when there is none. */
    fun redo(): MoveHistory {
        val move = redoable ?: return this
        return MoveHistory(start, state.apply(move), done + move, undone.dropLast(1))
    }

    /** Back at [start], with nothing to undo or redo. */
    fun reset(): MoveHistory = MoveHistory(start)

    /**
     * The same start, state and committed Moves with nothing to redo: what a Checkpoint holds, so
     * returning to it never brings back an undone Move.
     */
    fun withoutRedo(): MoveHistory = if (undone.isEmpty()) this else MoveHistory(start, state, done, emptyList())

    override fun equals(other: Any?): Boolean =
        other is MoveHistory && start == other.start && state == other.state && done == other.done && undone == other.undone

    override fun hashCode(): Int = ((start.hashCode() * 31 + state.hashCode()) * 31 + done.hashCode()) * 31 + undone.hashCode()

    override fun toString(): String =
        "MoveHistory(start=$start, state=$state, done=${Notation.format(done)}, undone=${Notation.format(undone)})"
}
