package viewforge.command

import viewforge.model.ComponentDef
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.findById
import viewforge.model.replaceNode
import viewforge.model.updateScreenRoot

/**
 * Reusable-component commands (D7). A [ComponentDef] is a top-level document entry — like a [Screen] —
 * so [AddComponent]/[RemoveComponent] transform [Project.components] directly, mirroring
 * [AddScreen]/[RemoveScreen]. Instances stay thin: a `vforge.userComponent` node carries the
 * referenced component id and the definition is resolved at render/codegen time (ADR-024), so editing
 * a definition updates every instance without touching the instances themselves.
 *
 * Like every mutation these flow through [Command] (CLAUDE.md rule 3), so adding a component, removing
 * it, and extracting a selection into one are all undoable.
 */

/**
 * Insert [component] at [index] in the component list. [index] is clamped into range on apply, so a
 * large index appends (how [extractComponent] adds a freshly created definition). The inverse removes
 * it again.
 */
data class AddComponent(val component: ComponentDef, val index: Int, override val label: String = "Add component") :
    Command {
    override fun apply(doc: Project): Project {
        val at = index.coerceIn(0, doc.components.size)
        val components = doc.components.toMutableList().apply { add(at, component) }
        return doc.copy(components = components)
    }

    override fun invert(doc: Project): Command = RemoveComponent(component.id)
}

/**
 * Remove the component [id]. The inverse restores it to its exact position, so [invert] reads the
 * definition and its index out of the pre-apply document (like [RemoveNode]). Absent id ⇒ a no-op with
 * a no-op inverse, keeping [History] consistent.
 *
 * This is the mechanical removal only; it does not touch instances that still reference [id]. The
 * editor is responsible for not orphaning references (the palette offers removal only for unused
 * components), exactly as [RemoveScreen] does not chase inbound navigation.
 */
data class RemoveComponent(val id: String, override val label: String = "Delete component") : Command {
    override fun apply(doc: Project): Project {
        if (doc.components.none { it.id == id }) return doc
        return doc.copy(components = doc.components.filterNot { it.id == id })
    }

    override fun invert(doc: Project): Command {
        val index = doc.components.indexOfFirst { it.id == id }
        val component = doc.components.getOrNull(index)
        return if (component != null) AddComponent(component, index) else NoOp
    }
}

/** Rename component [id] to [name]. Rename never affects codegen *structure*, only the emitted fn/file name. */
data class RenameComponent(val id: String, val name: String, override val label: String = "Rename component") :
    Command {
    override fun apply(doc: Project): Project {
        val components = doc.components.map { if (it.id == id) it.copy(name = name) else it }
        return doc.copy(components = components)
    }

    override fun invert(doc: Project): Command {
        val old = doc.components.firstOrNull { it.id == id } ?: return NoOp
        return RenameComponent(id, old.name)
    }
}

/**
 * Replace the node [id] in [screenId] with [replacement], keeping its position — the mechanical half of
 * [extractComponent] (swap a selected subtree for an instance node) and its exact inverse (swap the
 * instance back for the original subtree). Self-inverting: [invert] reads the node currently at [id] out
 * of the pre-apply document and swaps [replacement] back for it. Absent id ⇒ a no-op with a no-op
 * inverse.
 */
data class ReplaceNode(
    val screenId: String,
    val id: NodeId,
    val replacement: Node,
    override val label: String = "Replace",
) : Command {
    override fun apply(doc: Project): Project = doc.updateScreenRoot(screenId) { it.replaceNode(id, replacement) }

    override fun invert(doc: Project): Command {
        val old = doc.screens.firstOrNull { it.id == screenId }?.root?.findById(id) ?: return NoOp
        return ReplaceNode(screenId, replacement.id, old)
    }
}

/**
 * Extract the selected subtree [targetNodeId] in [screenId] into the reusable [component], leaving an
 * [instance] node (a `vforge.userComponent` referencing [component].id) in its place (D7). One undoable
 * step: the composite adds the definition, then swaps the subtree for the instance; undo reverses both.
 *
 * The caller builds both [component] (its `root` is the extracted subtree, keeping the subtree's node
 * ids so undo restores them intact) and [instance] (a fresh id, `componentId` = [component].id), so the
 * instance id is known up front and the inverse is deterministic — the same pattern as [AddNode]
 * carrying its whole node. Extraction can never create a cycle (a brand-new component references
 * nothing that references it), so no cycle guard is needed here; inserting an instance *into* a
 * component is where [viewforge.project] cycle validation applies.
 */
fun extractComponent(screenId: String, targetNodeId: NodeId, component: ComponentDef, instance: Node): Command =
    CompositeCommand(
        commands = listOf(
            AddComponent(component, index = Int.MAX_VALUE),
            ReplaceNode(screenId, targetNodeId, instance),
        ),
        label = "Extract component",
    )
