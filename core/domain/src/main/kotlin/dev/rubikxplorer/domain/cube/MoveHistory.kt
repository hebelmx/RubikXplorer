package dev.rubikxplorer.domain.cube

/**
 * The free cube's undo/redo history (FR-3): an immutable value starting from [CubeState.SOLVED].
 * [commit] pushes a Move and clears redo; [undo] and [redo] are no-ops when there is nothing to
 * undo or redo; [reset] returns to solved with both stacks empty. The free cube is not a Learning
 * Session, so this history is never persisted.
 */
class MoveHistory private constructor(
    /** The Cube State after every committed, not-undone Move. */
    val state: CubeState,
    private val done: List<Move>,
    private val undone: List<Move>,
) {
    constructor() : this(CubeState.SOLVED, emptyList(), emptyList())

    val canUndo: Boolean get() = done.isNotEmpty()

    val canRedo: Boolean get() = undone.isNotEmpty()

    /** The Move [undo] would take back, or `null`. */
    val undoable: Move? get() = done.lastOrNull()

    /** The Move [redo] would apply again, or `null`. */
    val redoable: Move? get() = undone.lastOrNull()

    /** Applies [move] and clears redo. */
    fun commit(move: Move): MoveHistory = MoveHistory(state.apply(move), done + move, emptyList())

    /** Takes back the last committed Move; unchanged when there is none. */
    fun undo(): MoveHistory {
        val move = undoable ?: return this
        return MoveHistory(state.apply(move.inverse()), done.dropLast(1), undone + move)
    }

    /** Applies the last undone Move again; unchanged when there is none. */
    fun redo(): MoveHistory {
        val move = redoable ?: return this
        return MoveHistory(state.apply(move), done + move, undone.dropLast(1))
    }

    /** Solved, with nothing to undo or redo. */
    fun reset(): MoveHistory = MoveHistory()

    override fun equals(other: Any?): Boolean = other is MoveHistory && state == other.state && done == other.done && undone == other.undone

    override fun hashCode(): Int = (state.hashCode() * 31 + done.hashCode()) * 31 + undone.hashCode()

    override fun toString(): String = "MoveHistory(state=$state, done=${Notation.format(done)}, undone=${Notation.format(undone)})"
}
