package viewforge.command

import viewforge.model.Action
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.findById
import viewforge.model.findRoot
import viewforge.model.replaceNode
import viewforge.model.updateRoot
import viewforge.model.withHandlers

/**
 * Event-handler editing (ADR-035, #277). A node's [viewforge.model.Node.handlers] slot holds an **ordered**
 * [Action] list; like every mutation these flow through [Command] so the inspector's action edits are undoable
 * (CLAUDE.md rule 3). One command replaces a whole slot list, so add, remove, reorder, and edit-in-place are all
 * just a different list — the same shape as [SetModifiers] for the modifier chain. Order is semantic (actions run
 * top to bottom) and preserved exactly.
 */

/**
 * Replace node [nodeId]'s event-slot [slot] with [actions], or clear the slot when [actions] is empty. Targets the
 * screen **or** component root identified by [rootId] via [updateRoot], so it edits either surface (ADR-027). Not
 * coalesced: each is a discrete structural edit. Absent node ⇒ unchanged; the inverse restores the prior list.
 */
data class SetHandler(
    val rootId: String,
    val nodeId: NodeId,
    val slot: String,
    val actions: List<Action>,
    override val label: String = "Edit $slot",
) : Command {
    override fun apply(doc: Project): Project = doc.updateRoot(rootId) { root ->
        val node = root.findById(nodeId) ?: return@updateRoot root
        root.replaceNode(nodeId, node.withHandlers(slot, actions))
    }

    override fun invert(doc: Project): Command {
        val old = doc.findRoot(rootId)?.findById(nodeId)?.handlers?.get(slot).orEmpty()
        return SetHandler(rootId, nodeId, slot, old, label)
    }
}
