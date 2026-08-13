package viewforge.packages.compose.render

import viewforge.model.ComponentDef
import viewforge.model.Node
import viewforge.model.PropValue

/**
 * Resolves a user component's parameters for one instance (parameters slice 3, ADR-028): returns the
 * definition's root tree with every [PropValue.ParamRef] replaced by the value the [instance] supplies
 * for that parameter (a prop keyed by the parameter name), falling back to the parameter's declared
 * default. A parameter with neither an argument nor a default resolves to nothing, so the prop is
 * dropped and the renderer uses its own omitted-arg default — the same fallback codegen relies on, so
 * the canvas and generated code agree (TECHNICAL_NOTES §2).
 *
 * The instance node holds argument values in its own props alongside `componentId` (DATA_MODEL §4);
 * substituting the definition's tree (never the instance's) means only definition-scope ParamRefs are
 * touched — a nested instance's argument props are also substituted, since those are expressions in
 * this definition's scope, and resolve again when that nested instance renders.
 *
 * Pure, so the substitution is unit-testable without a composition. A definition with no parameters
 * returns its root unchanged (no walk, no allocation).
 */
internal fun bindParameters(def: ComponentDef, instance: Node): Node {
    if (def.parameters.isEmpty()) return def.root
    val bindings: Map<String, PropValue?> =
        def.parameters.associate { p -> p.name to (instance.props[p.name] ?: p.default) }
    return def.root.substituteParams(bindings)
}

/** A ParamRef resolves to its binding (or null when unbound); any other value is returned unchanged. */
private fun PropValue.resolveParam(bindings: Map<String, PropValue?>): PropValue? =
    if (this is PropValue.ParamRef) bindings[param] else this

private fun Map<String, PropValue>.substituteParams(bindings: Map<String, PropValue?>): Map<String, PropValue> =
    buildMap {
        this@substituteParams.forEach { (key, value) -> value.resolveParam(bindings)?.let { put(key, it) } }
    }

private fun Node.substituteParams(bindings: Map<String, PropValue?>): Node = copy(
    props = props.substituteParams(bindings),
    modifiers = modifiers.map { it.copy(args = it.args.substituteParams(bindings)) },
    children = children.map { it.substituteParams(bindings) },
    slots = slots.mapValues { (_, list) -> list.map { it.substituteParams(bindings) } },
)
