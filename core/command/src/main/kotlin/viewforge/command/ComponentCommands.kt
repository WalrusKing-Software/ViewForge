package viewforge.command

import viewforge.model.ComponentDef
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Parameter
import viewforge.model.Project
import viewforge.model.PropValue
import viewforge.model.findById
import viewforge.model.findRoot
import viewforge.model.replaceNode
import viewforge.model.updateRoot

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
 * Replace the node [id] in the root [rootId] (a screen or component, ADR-027) with [replacement],
 * keeping its position — the mechanical half of [extractComponent] (swap a selected subtree for an
 * instance node) and its exact inverse (swap the instance back for the original subtree). Self-inverting:
 * [invert] reads the node currently at [id] out of the pre-apply document and swaps [replacement] back
 * for it. Absent id ⇒ a no-op with a no-op inverse.
 */
data class ReplaceNode(
    val rootId: String,
    val id: NodeId,
    val replacement: Node,
    override val label: String = "Replace",
) : Command {
    override fun apply(doc: Project): Project = doc.updateRoot(rootId) { it.replaceNode(id, replacement) }

    override fun invert(doc: Project): Command {
        val old = doc.findRoot(rootId)?.findById(id) ?: return NoOp
        return ReplaceNode(rootId, replacement.id, old)
    }
}

/**
 * Extract the selected subtree [targetNodeId] in the root [rootId] (a screen or component) into the
 * reusable [component], leaving an [instance] node (a `vforge.userComponent` referencing [component].id)
 * in its place (D7). One undoable step: the composite adds the definition, then swaps the subtree for the
 * instance; undo reverses both.
 *
 * The caller builds both [component] (its `root` is the extracted subtree, keeping the subtree's node
 * ids so undo restores them intact) and [instance] (a fresh id, `componentId` = [component].id), so the
 * instance id is known up front and the inverse is deterministic — the same pattern as [AddNode]
 * carrying its whole node. Extraction can never create a cycle (a brand-new component references
 * nothing that references it), so no cycle guard is needed here; inserting an instance *into* a
 * component is where [viewforge.project] cycle validation applies.
 */
fun extractComponent(rootId: String, targetNodeId: NodeId, component: ComponentDef, instance: Node): Command =
    CompositeCommand(
        commands = listOf(
            AddComponent(component, index = Int.MAX_VALUE),
            ReplaceNode(rootId, targetNodeId, instance),
        ),
        label = "Extract component",
    )

/**
 * Publish [component] as a new reusable component (#184) — the "save a screen as a palette component"
 * authoring action. Unlike [extractComponent], which *moves* a selected subtree and leaves an instance
 * in its place, this *copies*: the caller builds [component] from a fresh-id copy of a screen's root
 * ([viewforge.model.withFreshIds]), so the source screen is untouched and the two roots never share node
 * ids. That makes it a plain [AddComponent] (undo removes the definition; the screen was never modified),
 * so no composite is needed. The definition surfaces in the palette immediately (the editor lists
 * [Project.components]).
 */
fun promoteScreenToComponent(component: ComponentDef): Command =
    AddComponent(component, index = Int.MAX_VALUE, label = "Save screen as component")

/**
 * Add [parameter] at [index] to component [componentId]'s parameter list (ADR-028). [index] is clamped,
 * so a large index appends (how [promoteToParameter] adds a freshly derived parameter). Parameter names
 * are unique within a component (two would generate a duplicate function parameter), so a name already
 * present ⇒ a no-op with a no-op inverse. The inverse removes the parameter again.
 */
data class AddParameter(
    val componentId: String,
    val parameter: Parameter,
    val index: Int,
    override val label: String = "Add parameter",
) : Command {
    override fun apply(doc: Project): Project {
        val component = doc.components.firstOrNull { it.id == componentId } ?: return doc
        if (component.parameters.any { it.name == parameter.name }) return doc
        val at = index.coerceIn(0, component.parameters.size)
        val parameters = component.parameters.toMutableList().apply { add(at, parameter) }
        return doc.withComponentParameters(componentId, parameters)
    }

    override fun invert(doc: Project): Command {
        // If the name is already present in the pre-apply document, apply was a no-op, so undo must be
        // too — otherwise the inverse would remove the parameter that was already there.
        val duplicate = doc.components.firstOrNull {
            it.id == componentId
        }?.parameters?.any { it.name == parameter.name }
        return if (duplicate == true) NoOp else RemoveParameter(componentId, parameter.name)
    }
}

/**
 * Remove the parameter [name] from component [componentId]. The inverse restores it to its exact
 * position (reads the parameter and its index out of the pre-apply document, like [RemoveComponent]).
 * Absent component or name ⇒ a no-op with a no-op inverse. Mechanical only — it does not rewrite any
 * `ParamRef` in the definition body that still names [name]; the editor owns not orphaning references.
 */
data class RemoveParameter(
    val componentId: String,
    val name: String,
    override val label: String = "Remove parameter",
) : Command {
    override fun apply(doc: Project): Project {
        val component = doc.components.firstOrNull { it.id == componentId } ?: return doc
        if (component.parameters.none { it.name == name }) return doc
        val parameters = component.parameters.filterNot { it.name == name }
        return doc.withComponentParameters(componentId, parameters)
    }

    override fun invert(doc: Project): Command {
        val component = doc.components.firstOrNull { it.id == componentId } ?: return NoOp
        val index = component.parameters.indexOfFirst { it.name == name }
        val parameter = component.parameters.getOrNull(index) ?: return NoOp
        return AddParameter(componentId, parameter, index)
    }
}

/**
 * Promote a prop of node [nodeId] in component [componentId] to a component [parameter] (ADR-028): add
 * the parameter, then rebind that prop to a `ParamRef` naming it. One undoable step (the composite adds
 * the parameter and rebinds the prop; undo reverses both). The caller derives [parameter] (its name,
 * type, and default) from the prop being promoted, so the change is deterministic.
 */
fun promoteToParameter(componentId: String, nodeId: NodeId, propName: String, parameter: Parameter): Command =
    CompositeCommand(
        commands = listOf(
            AddParameter(componentId, parameter, index = Int.MAX_VALUE),
            SetProp(componentId, nodeId, propName, PropValue.ParamRef(parameter.name)),
        ),
        label = "Promote to parameter",
    )

/** Return a copy of this project with component [componentId]'s parameter list replaced by [parameters]. */
private fun Project.withComponentParameters(componentId: String, parameters: List<Parameter>): Project =
    copy(components = components.map { if (it.id == componentId) it.copy(parameters = parameters) else it })
