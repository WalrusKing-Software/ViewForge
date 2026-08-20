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

    /**
     * A Material shape, edited as either a literal corner radius ([PropValue.Literal], dp →
     * `RoundedCornerShape`) or a `shapes.small|medium|large` theme token ([PropValue.ThemeRef] →
     * `MaterialTheme.shapes.<slot>`). Themeable, like [Color] (e.g. `Button.shape`).
     */
    Shape,

    /** An imported asset, edited as a [PropValue.ResourceRef] via an asset picker (e.g. `Image.source`). */
    Resource,
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

/**
 * One event slot a component exposes (ADR-035, #277) — a closed, named handler point like a Button's
 * `onClick`. The catalog declares these the same data-driven way it declares [PropDefinition]s, so the
 * inspector's action editor is generated from the list and adding a component's event slot needs **no
 * per-component inspector UI** (the standing anti-pattern). [name] is the [Node.handlers] map key and
 * must match what the renderer/codegen read (e.g. [EventSlots.ON_CLICK]); [label] is the display text.
 * Like the prop/modifier schema, this is runtime capability metadata, not persisted (ADR-016).
 */
data class EventSlotDefinition(val name: String, val label: String)

/**
 * The well-known event-slot names (ADR-035, #277), single-sourced here so the catalog (which declares a
 * component's slots), the renderer (which dispatches a slot's actions in C13 run mode), and codegen (which
 * lowers them to handler lambdas) all agree on one string. Only `onClick` is live end-to-end this slice;
 * the inert slots (`onCheckedChange`, `onValueChange`) are named for when they are wired.
 */
object EventSlots {
    const val ON_CLICK: String = "onClick"
}
