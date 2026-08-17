package viewforge.packages.compose.catalog

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ModifierArg
import viewforge.model.ModifierDefinition
import viewforge.model.PropType
import viewforge.model.PropValue

/**
 * The Compose package's modifier catalog: the schema for every modifier the inspector may add, with
 * its editable args. Like the component catalog it mirrors **only what the renderer applies**
 * (`render/Modifiers.kt`) *and* what codegen emits (`codegen/ModifierEmitter.kt`) — as of M6 the two
 * cover this list in lockstep, so the editor never offers a modifier the canvas ignores or the
 * emitter drops (ADR-015, ADR-018). The broader DATA_MODEL §7 allowlist (border, clip, alpha, …)
 * joins this list one modifier at a time, each with render + codegen + a golden test; deferred past M6.
 *
 * Arg types match how `render/Values.kt` reads them: `padding`/`size`/`width`/`height` args are `Dp`
 * integers, `background`'s `color` is a themeable color.
 */
object ComposeModifiers {
    val definitions: List<ModifierDefinition> = listOf(
        ModifierDefinition("compose.fillMaxSize", "Fill Max Size"),
        ModifierDefinition("compose.fillMaxWidth", "Fill Max Width"),
        ModifierDefinition("compose.fillMaxHeight", "Fill Max Height"),
        ModifierDefinition(
            "compose.padding",
            "Padding",
            args = listOf(
                dp("all", default = 16),
                dp("start"),
                dp("top"),
                dp("end"),
                dp("bottom"),
            ),
        ),
        ModifierDefinition(
            "compose.size",
            "Size",
            args = listOf(dp("width", default = 100), dp("height", default = 100)),
        ),
        ModifierDefinition("compose.width", "Width", args = listOf(dp("value", default = 100))),
        ModifierDefinition("compose.height", "Height", args = listOf(dp("value", default = 100))),
        ModifierDefinition(
            "compose.background",
            "Background",
            args = listOf(ModifierArg("color", PropType.Color, default = colorLiteral("#2196F3"), themeable = true)),
        ),
        // weight is a RowScope/ColumnScope extension, so it is only valid on a direct child of a Row or
        // Column (see [scopedTypes]); the inspector gates it via [offeredFor], and render/codegen drop it
        // where the parent scope isn't present. A non-positive weight is treated as a no-op in both.
        ModifierDefinition("compose.weight", "Weight", args = listOf(float("weight", default = 1))),
    )

    /** The parent types that provide the `RowScope`/`ColumnScope` a `weight` modifier needs. */
    val scopedParentTypes: Set<String> = setOf(
        "compose.foundation.layout.Row",
        "compose.foundation.layout.Column",
    )

    private val byType: Map<String, ModifierDefinition> = definitions.associateBy { it.type }

    fun definitionFor(type: String): ModifierDefinition? = byType[type]

    /**
     * The modifiers offerable to a node whose parent is [parentType] (null at the root). Scope-only
     * modifiers — currently just `weight` — are withheld unless the parent supplies their scope, so the
     * inspector never adds one the canvas can't honor or the emitter would emit as non-compiling code.
     */
    fun offeredFor(parentType: String?): List<ModifierDefinition> =
        if (parentType in scopedParentTypes) definitions else definitions.filterNot { it.type == "compose.weight" }

    private fun dp(name: String, default: Int? = null): ModifierArg =
        ModifierArg(name, PropType.Dp, default = default?.let { PropValue.Literal(JsonPrimitive(it)) })

    private fun float(name: String, default: Int): ModifierArg =
        ModifierArg(name, PropType.Float, default = PropValue.Literal(JsonPrimitive(default)))

    private fun colorLiteral(hex: String): PropValue = PropValue.Literal(JsonPrimitive(hex))
}
