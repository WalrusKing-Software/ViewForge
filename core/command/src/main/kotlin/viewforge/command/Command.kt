package viewforge.command

import viewforge.model.ChildAddress
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.findById
import viewforge.model.findRoot
import viewforge.model.insertChild
import viewforge.model.locate
import viewforge.model.removeChild
import viewforge.model.replaceNode
import viewforge.model.updateRoot

/**
 * A single undoable document mutation (ARCHITECTURE §5, CLAUDE.md rule 3). Every change to the IR is
 * a [Command] — there is no direct mutation path — so undo/redo (and, later, collaboration) can
 * replay and reverse the whole edit stream.
 *
 * Commands are **pure**: [apply] returns a new [Project] (structural sharing via the `core/model`
 * edit ops) and never touches the input. [invert] returns the command that undoes this one, computed
 * against the document *as it was before* [apply] ran — so an invert may need to read state (a
 * removed node, an old name) out of that pre-image. [History] captures the inverse once at execute
 * time, when that pre-image is in hand.
 */
interface Command {
    /** A short human label for menus/history ("Add Text", "Delete Button"). */
    val label: String

    /**
     * A key that lets a run of like edits collapse into one undo step (D3, ADR-017). When non-null and
     * equal to the previous command's key, [History] merges them — a stepper drag or hex typing becomes
     * a single history entry instead of hundreds. Null (the default) never coalesces.
     */
    val coalesceKey: Any? get() = null

    /** The document after this command. Must not mutate [doc]. */
    fun apply(doc: Project): Project

    /**
     * The command that reverses this one. [doc] is the document **before** [apply] was called; an
     * implementation reads whatever pre-state it needs from it (e.g. a node about to be removed).
     */
    fun invert(doc: Project): Command
}

/**
 * A no-op-safe wrapper so the same tree edit reads identically across commands: locate the target root
 * — a screen **or** a component (ADR-027) — transform it, keep every other container by identity
 * ([Project.updateRoot]).
 */
private fun Project.editRoot(rootId: String, transform: (Node) -> Node): Project = updateRoot(rootId, transform)

/**
 * Insert [node] at [address] within the root [rootId] (a screen or component, ADR-027). Inverse removes
 * it again.
 */
data class AddNode(
    val rootId: String,
    val address: ChildAddress,
    val node: Node,
    override val label: String = "Add ${node.type.substringAfterLast('.')}",
) : Command {
    override fun apply(doc: Project): Project = doc.editRoot(rootId) { it.insertChild(address, node) }

    override fun invert(doc: Project): Command = RemoveNode(rootId, node.id)
}

/**
 * Remove the node [id] from the root [rootId] (a screen or component), wherever it sits. The inverse
 * restores it to the exact same position, so [invert] must read the node and its address out of the
 * pre-apply document.
 */
data class RemoveNode(val rootId: String, val id: NodeId, override val label: String = "Delete") : Command {
    override fun apply(doc: Project): Project = doc.editRoot(rootId) { it.removeChild(id) }

    override fun invert(doc: Project): Command {
        val root = doc.findRoot(rootId)
        val address = root?.locate(id)
        val node = root?.findById(id)
        // If the node isn't present, apply() is a no-op; a no-op inverse keeps History consistent.
        return if (address != null && node != null) AddNode(rootId, address, node) else NoOp
    }
}

/**
 * Move [id] to [target] within the root [rootId] — the single command behind both reorder and reparent.
 * Apply is remove-then-insert, so [target].index is interpreted against the post-removal list
 * (see [ChildAddress]). The inverse moves it back to where it started.
 */
data class MoveNode(
    val rootId: String,
    val id: NodeId,
    val target: ChildAddress,
    override val label: String = "Move",
) : Command {
    override fun apply(doc: Project): Project = doc.editRoot(rootId) { root ->
        val node = root.findById(id) ?: return@editRoot root
        root.removeChild(id).insertChild(target, node)
    }

    override fun invert(doc: Project): Command {
        val origin = doc.findRoot(rootId)?.locate(id)
        return if (origin != null) MoveNode(rootId, id, origin) else NoOp
    }
}

/** Set (or clear) [Node.name] on [id]. Rename never affects codegen structure (T3). */
data class RenameNode(val rootId: String, val id: NodeId, val name: String?, override val label: String = "Rename") :
    Command {
    override fun apply(doc: Project): Project = doc.editRoot(rootId) { root ->
        val node = root.findById(id) ?: return@editRoot root
        root.replaceNode(id, node.copy(name = name?.takeIf { it.isNotBlank() }))
    }

    override fun invert(doc: Project): Command {
        val old = doc.findRoot(rootId)?.findById(id)?.name
        return RenameNode(rootId, id, old)
    }
}

/**
 * Toggle the editor flags on [id]. `locked` blocks selection; `hidden` removes the node from both
 * render and codegen (T4, DATA_MODEL §5). A null argument leaves that flag untouched.
 */
data class SetNodeFlags(
    val rootId: String,
    val id: NodeId,
    val locked: Boolean? = null,
    val hidden: Boolean? = null,
    override val label: String = "Toggle flag",
) : Command {
    override fun apply(doc: Project): Project = doc.editRoot(rootId) { root ->
        val node = root.findById(id) ?: return@editRoot root
        root.replaceNode(
            id,
            node.copy(
                locked = locked ?: node.locked,
                hidden = hidden ?: node.hidden,
            ),
        )
    }

    override fun invert(doc: Project): Command {
        val node = doc.findRoot(rootId)?.findById(id)
        return SetNodeFlags(
            rootId,
            id,
            locked = if (locked != null) node?.locked else null,
            hidden = if (hidden != null) node?.hidden else null,
        )
    }
}

/**
 * Several commands applied and undone as one history entry (ARCHITECTURE §5). Used where one gesture
 * is logically atomic (a cut = capture + remove); the seam M5's slider/drag coalescing grows from.
 * The inverse is the child inverses in reverse order, each computed against the intermediate state.
 */
data class CompositeCommand(
    val commands: List<Command>,
    override val label: String = "Edit",
    override val coalesceKey: Any? = null,
) : Command {
    override fun apply(doc: Project): Project = commands.fold(doc) { acc, cmd -> cmd.apply(acc) }

    override fun invert(doc: Project): Command {
        val inverses = ArrayList<Command>(commands.size)
        var state = doc
        for (cmd in commands) {
            inverses.add(cmd.invert(state))
            state = cmd.apply(state)
        }
        return CompositeCommand(inverses.asReversed(), label)
    }
}

/** The identity command: applies unchanged, inverts to itself. Keeps history total when a target vanished. */
object NoOp : Command {
    override val label: String = "No-op"

    override fun apply(doc: Project): Project = doc

    override fun invert(doc: Project): Command = this
}
