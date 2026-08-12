package viewforge.command

import viewforge.model.ModifierEntry
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropValue
import viewforge.model.findById
import viewforge.model.findRoot
import viewforge.model.replaceNode
import viewforge.model.updateRoot
import viewforge.model.withModifiers
import viewforge.model.withProp

/**
 * Property- and modifier-editing commands (M5). Like every mutation they go through [Command] so the
 * inspector's edits are undoable (CLAUDE.md rule 3). The continuous ones ([SetProp], [SetModifierArg])
 * carry a [Command.coalesceKey] so a stepper drag or hex typing collapses to one undo step (D3).
 */

/**
 * Set node [nodeId]'s prop [key] to [value], or remove it when [value] is null (reset to default, I7).
 * Coalesces per (node, prop) so typing into a field is one history entry.
 */
data class SetProp(
    val rootId: String,
    val nodeId: NodeId,
    val key: String,
    val value: PropValue?,
    override val label: String = "Edit $key",
) : Command {
    override val coalesceKey: Any = Triple(nodeId, "prop", key)

    override fun apply(doc: Project): Project = doc.updateRoot(rootId) { root ->
        val node = root.findById(nodeId) ?: return@updateRoot root
        root.replaceNode(nodeId, node.withProp(key, value))
    }

    override fun invert(doc: Project): Command {
        val old = doc.findRoot(rootId)?.findById(nodeId)?.props?.get(key)
        return SetProp(rootId, nodeId, key, old, label)
    }
}

/**
 * Replace node [nodeId]'s **ordered** modifier chain — the one command behind add, remove, reorder,
 * and enable-toggle, since all of those are just a different list. Not coalesced: each is a discrete
 * structural edit. Order is preserved exactly (ADR-005).
 */
data class SetModifiers(
    val rootId: String,
    val nodeId: NodeId,
    val modifiers: List<ModifierEntry>,
    override val label: String = "Edit modifiers",
) : Command {
    override fun apply(doc: Project): Project = doc.updateRoot(rootId) { root ->
        val node = root.findById(nodeId) ?: return@updateRoot root
        root.replaceNode(nodeId, node.withModifiers(modifiers))
    }

    override fun invert(doc: Project): Command {
        val old = doc.findRoot(rootId)?.findById(nodeId)?.modifiers.orEmpty()
        return SetModifiers(rootId, nodeId, old, label)
    }
}

/**
 * Set (or remove, when [value] is null) one arg [key] of the modifier [modifierId] on node [nodeId].
 * Coalesces per (node, modifier, arg) so dragging a Dp stepper is one undo step. A no-op if the
 * modifier isn't present.
 */
data class SetModifierArg(
    val rootId: String,
    val nodeId: NodeId,
    val modifierId: String,
    val key: String,
    val value: PropValue?,
    override val label: String = "Edit $key",
) : Command {
    override val coalesceKey: Any = Triple(nodeId, modifierId, key)

    override fun apply(doc: Project): Project = doc.updateRoot(rootId) { root ->
        val node = root.findById(nodeId) ?: return@updateRoot root
        val updated = node.modifiers.map { entry ->
            if (entry.id != modifierId) {
                entry
            } else {
                val args = if (value == null) entry.args - key else entry.args + (key to value)
                entry.copy(args = args)
            }
        }
        root.replaceNode(nodeId, node.withModifiers(updated))
    }

    override fun invert(doc: Project): Command {
        val old = doc.findRoot(rootId)?.findById(nodeId)
            ?.modifiers?.firstOrNull { it.id == modifierId }?.args?.get(key)
        return SetModifierArg(rootId, nodeId, modifierId, key, old, label)
    }
}
