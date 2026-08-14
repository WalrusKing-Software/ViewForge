package viewforge.command

import viewforge.model.Project

/**
 * The undo/redo stacks (ARCHITECTURE §5). Commands flow through here so every mutation is reversible
 * and the edit stream is a single, replayable log.
 *
 * Each executed command is stored together with the inverse captured **once, at execute time**, when
 * the pre-apply document is in hand. This is correct for a linear history: `undo` returns the document
 * to exactly the pre-apply state, so a later `redo` re-applies the original command from that same
 * state, and the stored inverse is valid again. A new `execute` clears the redo stack (the standard
 * linear-history rule — you cannot redo down a branch you have diverged from).
 *
 * History is **not** part of the document and is never serialized; it is cleared when a document is
 * closed. It is capped at [limit] entries, dropping the oldest, so an unbounded session can't grow
 * memory without bound.
 *
 * [limit] is adjustable at runtime (S5, #105: it is a user preference): lowering it trims the oldest undo
 * entries immediately so the cap is honoured the moment it changes, not only on the next `execute`.
 */
class History(limit: Int = DEFAULT_LIMIT) {
    /** An applied command paired with the inverse that undoes it. */
    private data class Entry(val command: Command, val inverse: Command)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    /** The maximum number of undo entries kept; oldest are dropped past it. Trims immediately when lowered. */
    var limit: Int = limit
        set(value) {
            field = value
            while (undoStack.size > field) undoStack.removeFirst()
        }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Label of the change undo would reverse, or null — for menu text ("Undo Move"). */
    val undoLabel: String? get() = undoStack.lastOrNull()?.command?.label

    /** Label of the change redo would re-apply, or null. */
    val redoLabel: String? get() = redoStack.lastOrNull()?.command?.label

    /**
     * Apply [command] to [doc], record it for undo, and return the new document. Capturing the inverse
     * here — against [doc], the pre-apply image — is what lets [invert] read removed nodes / old
     * values. Clears the redo stack.
     *
     * **Coalescing (D3):** when [command] shares a non-null [Command.coalesceKey] with the top undo
     * entry (and nothing has been undone), the two merge into one entry — the *original* inverse is
     * kept (so undo reverts the whole run) while the latest command replaces it for redo. This turns a
     * stepper drag or a burst of keystrokes into a single history step.
     */
    fun execute(command: Command, doc: Project): Project {
        val next = command.apply(doc)
        val top = undoStack.lastOrNull()
        val coalesces = command.coalesceKey != null &&
            redoStack.isEmpty() &&
            top != null &&
            top.command.coalesceKey == command.coalesceKey
        if (coalesces) {
            undoStack[undoStack.lastIndex] = top!!.copy(command = command)
        } else {
            val inverse = command.invert(doc)
            redoStack.clear()
            undoStack.addLast(Entry(command, inverse))
            while (undoStack.size > limit) undoStack.removeFirst()
        }
        return next
    }

    /** Undo the most recent change, returning the prior document. [doc] unchanged if [canUndo] is false. */
    fun undo(doc: Project): Project {
        val entry = undoStack.removeLastOrNull() ?: return doc
        redoStack.addLast(entry)
        return entry.inverse.apply(doc)
    }

    /** Redo the most recently undone change, returning the re-applied document. No-op if [canRedo] is false. */
    fun redo(doc: Project): Project {
        val entry = redoStack.removeLastOrNull() ?: return doc
        undoStack.addLast(entry)
        return entry.command.apply(doc)
    }

    /** Drop all history (document close). */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    companion object {
        /** Default history depth (ARCHITECTURE §5: "configurable; default ~200 entries"). */
        const val DEFAULT_LIMIT = 200
    }
}
