package viewforge.packages.compose.catalog

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ModifierArg
import viewforge.model.ModifierDefinition
import viewforge.model.PropType
import viewforge.model.PropValue

/**
 * The Compose package's modifier catalog: the schema for every modifier the inspector may add, with
 * its editable args. Like the component catalog it mirrors **only what the renderer can actually
 * apply** (`render/Modifiers.kt`) — offering a modifier the canvas ignores would make the editor lie
 * (ADR-015). The broader DATA_MODEL §7 allowlist (border, clip, alpha, …) joins this list as the
 * renderer and codegen gain support at M6.
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
    )

    private val byType: Map<String, ModifierDefinition> = definitions.associateBy { it.type }

    fun definitionFor(type: String): ModifierDefinition? = byType[type]

    private fun dp(name: String, default: Int? = null): ModifierArg =
        ModifierArg(name, PropType.Dp, default = default?.let { PropValue.Literal(JsonPrimitive(it)) })

    private fun colorLiteral(hex: String): PropValue = PropValue.Literal(JsonPrimitive(hex))
}
