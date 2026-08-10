package viewforge.packages.compose.catalog

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ModifierEntry
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropDefinition
import viewforge.model.PropType
import viewforge.model.PropValue
import viewforge.model.Ulid

/**
 * The Compose framework package's component catalog: the schema half of the SPI (ARCHITECTURE §6.2)
 * as plain, Compose-free data. It lists what the package offers to the palette, how to spawn a fresh
 * instance of each, and each type's container facts (default children / named slots) for drop
 * validation.
 *
 * It deliberately mirrors **only the currently supported set** (Column, Row, Box, Spacer, Text,
 * Button), which as of M6 the renderer (`render/Components.kt`) *and* codegen
 * (`codegen/ComponentEmitter.kt`) both cover in lockstep — the palette never offers a node the canvas
 * can't draw or the emitter can't generate. Growing the set (Card, TextField, …) is a
 * renderer + emitter + golden-test triple per component, deferred past M6 (depth over breadth, M9).
 * `:app` adapts this to the editor's `ComponentCatalog` seam (ADR-013); nothing here depends on the
 * editor or on Compose UI types.
 */
object ComposeComponents {
    /** One catalog entry: palette metadata, container facts, an editable prop schema, and a factory. */
    data class Spec(
        val type: String,
        val label: String,
        val category: String,
        val acceptsChildren: Boolean,
        val slots: List<String>,
        val props: List<PropDefinition> = emptyList(),
        val create: () -> Node,
    )

    // These lists MUST match the enums the renderer parses in `render/Values.kt`; a mismatch would let
    // the inspector write a value the canvas silently ignores. Declared before `specs`, which uses them.
    private val H_ALIGN = listOf("Start", "CenterHorizontally", "End")
    private val V_ALIGN = listOf("Top", "CenterVertically", "Bottom")
    private val V_ARRANGE = listOf("Top", "Center", "Bottom", "SpaceBetween", "SpaceAround", "SpaceEvenly")
    private val H_ARRANGE = listOf("Start", "Center", "End", "SpaceBetween", "SpaceAround", "SpaceEvenly")
    private val BOX_ALIGN = listOf(
        "TopStart", "TopCenter", "TopEnd",
        "CenterStart", "Center", "CenterEnd",
        "BottomStart", "BottomCenter", "BottomEnd",
    )

    val specs: List<Spec> = listOf(
        Spec(
            "compose.foundation.layout.Column",
            "Column",
            CATEGORY_LAYOUT,
            acceptsChildren = true,
            slots = emptyList(),
            props = listOf(
                enumProp("horizontalAlignment", H_ALIGN, default = "Start"),
                enumProp("verticalArrangement", V_ARRANGE, default = "Top"),
            ),
        ) {
            Node(id = NodeId.random(), type = "compose.foundation.layout.Column")
        },
        Spec(
            "compose.foundation.layout.Row",
            "Row",
            CATEGORY_LAYOUT,
            acceptsChildren = true,
            slots = emptyList(),
            props = listOf(
                enumProp("horizontalArrangement", H_ARRANGE, default = "Start"),
                enumProp("verticalAlignment", V_ALIGN, default = "Top"),
            ),
        ) {
            Node(id = NodeId.random(), type = "compose.foundation.layout.Row")
        },
        Spec(
            "compose.foundation.layout.Box",
            "Box",
            CATEGORY_LAYOUT,
            acceptsChildren = true,
            slots = emptyList(),
            props = listOf(enumProp("contentAlignment", BOX_ALIGN, default = "TopStart")),
        ) {
            Node(id = NodeId.random(), type = "compose.foundation.layout.Box")
        },
        Spec(
            "compose.foundation.layout.Spacer",
            "Spacer",
            CATEGORY_LAYOUT,
            acceptsChildren = false,
            slots = emptyList(),
        ) {
            // A visible default size so a fresh Spacer occupies space rather than collapsing to zero.
            Node(
                id = NodeId.random(),
                type = "compose.foundation.layout.Spacer",
                modifiers = listOf(
                    ModifierEntry(id = Ulid.next(), type = "compose.size", args = mapOf("all" to intLiteral(16))),
                ),
            )
        },
        Spec(
            "compose.material3.Text",
            "Text",
            CATEGORY_CONTENT,
            acceptsChildren = false,
            slots = emptyList(),
            props = listOf(
                PropDefinition("text", PropType.String, default = stringLiteral("")),
                PropDefinition("color", PropType.Color, themeable = true),
                PropDefinition("style", PropType.Typography, themeable = true),
            ),
        ) {
            Node(id = NodeId.random(), type = "compose.material3.Text", props = mapOf("text" to stringLiteral("Text")))
        },
        Spec(
            "compose.material3.Button",
            "Button",
            CATEGORY_INPUT,
            acceptsChildren = false,
            slots = listOf("content"),
            // onClick is an expression prop — never evaluated on the canvas (PF-4); edited via the
            // raw-expression hatch, so its control renders whatever RawExpression it holds.
            props = listOf(
                PropDefinition("onClick", PropType.String, default = PropValue.RawExpression("{}"), advanced = true),
            ),
        ) {
            Node(
                id = NodeId.random(),
                type = "compose.material3.Button",
                props = mapOf("onClick" to PropValue.RawExpression("{ /* TODO */ }")),
                slots = mapOf(
                    "content" to listOf(
                        Node(
                            id = NodeId.random(),
                            type = "compose.material3.Text",
                            props = mapOf(
                                "text" to stringLiteral("Button"),
                            ),
                        ),
                    ),
                ),
            )
        },
    )

    private val byType: Map<String, Spec> = specs.associateBy { it.type }

    fun specFor(type: String): Spec? = byType[type]

    private fun stringLiteral(value: String): PropValue = PropValue.Literal(JsonPrimitive(value))

    private fun intLiteral(value: Int): PropValue = PropValue.Literal(JsonPrimitive(value))

    /** An enum prop whose literal string is one of [values] — kept in lockstep with the `Values.kt` parsers. */
    private fun enumProp(name: String, values: List<String>, default: String): PropDefinition =
        PropDefinition(name, PropType.Enum, default = stringLiteral(default), enumValues = values)

    private const val CATEGORY_LAYOUT = "Layout"
    private const val CATEGORY_CONTENT = "Content"
    private const val CATEGORY_INPUT = "Input"
}
