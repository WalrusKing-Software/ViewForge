package viewforge.model

/**
 * The schema half of the component model (DATA_MODEL §6): typed descriptions of a component's props
 * and a modifier's args. Supplied by a framework package at runtime and used to **drive the inspector
 * and validation** — the inspector is generated from these, so adding a component never requires
 * inspector UI (I1, CLAUDE.md anti-patterns).
 *
 * These live in `core/model` (not the editor) because they are pure, Compose-free schema that both
 * the framework package and the editor need, and they reference [PropValue], which is already here.
 * They are **not** serialized: they describe the package's capabilities at runtime, they are not part
 * of the persisted `.vforge` document (ADR-016).
 */

/** The kind of value a prop/arg holds; determines the inspector control (DATA_MODEL §6). */
enum class PropType {
    String,
    Int,
    Float,
    Bool,
    Color,
    Dp,
    Enum,
    Typography,
}

/**
 * A single editable property of a component.
 *
 * @property default the value used when the prop is absent; also the target of "reset to default" (I7).
 * @property enumValues the allowed literals when [type] is [PropType.Enum] (e.g. alignment names).
 * @property range optional numeric bounds for [PropType.Int]/[PropType.Float] (drives a slider + validation).
 * @property themeable whether the value may bind to a theme token instead of a literal (I4).
 * @property advanced hint that a control should be tucked behind a disclosure (rarely edited props).
 */
data class PropDefinition(
    val name: String,
    val type: PropType,
    val default: PropValue? = null,
    val enumValues: List<String>? = null,
    val range: ClosedFloatingPointRange<Float>? = null,
    val themeable: Boolean = false,
    val advanced: Boolean = false,
    val description: String? = null,
)

/** One argument of a modifier (e.g. `padding`'s `all`, `background`'s `color`). */
data class ModifierArg(
    val name: String,
    val type: PropType,
    val default: PropValue? = null,
    val themeable: Boolean = false,
    val range: ClosedFloatingPointRange<Float>? = null,
)

/**
 * A modifier the package offers, with its arg schema. Only modifiers the renderer can actually apply
 * should be exposed, so the inspector never adds a modifier the canvas can't honor (same honesty rule
 * as the palette, ADR-015).
 */
data class ModifierDefinition(val type: String, val label: String, val args: List<ModifierArg> = emptyList())
