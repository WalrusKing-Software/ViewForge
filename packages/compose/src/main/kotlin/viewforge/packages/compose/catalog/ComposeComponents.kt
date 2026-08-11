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
 * It mirrors **only the currently supported set** — the same components the renderer
 * (`render/Components.kt`) *and* codegen (`codegen/ComponentEmitter.kt`) both cover in lockstep, so the
 * palette never offers a node the canvas can't draw or the emitter can't generate. Growing the set
 * (issue #16) is a renderer + emitter + golden-test triple per component (depth before breadth).
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

    // Kept in lockstep with the `ImageScale` enum the renderer parses in `render/Values.kt` and codegen
    // emits as `ContentScale.<value>`.
    private val CONTENT_SCALE = listOf("Fit", "Crop", "FillBounds", "Inside", "None")

    // The curated `Icon` set — must be a subset of `render/Values.kt`'s ICON_NAMES (CatalogConsistencyTest
    // fails otherwise). Each maps to `Icons.Filled.<name>` in both the renderer and codegen.
    private val ICONS = listOf(
        "Home", "Settings", "Search", "Menu", "Close", "Check", "Add", "Delete", "Edit", "Favorite",
        "Star", "Info", "Warning", "ArrowBack", "ArrowForward", "Person", "Share", "ShoppingCart",
        "Refresh", "MoreVert",
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
            "compose.foundation.lazy.LazyColumn",
            "LazyColumn",
            CATEGORY_LAYOUT,
            acceptsChildren = true,
            slots = emptyList(),
            props = listOf(
                enumProp("verticalArrangement", V_ARRANGE, default = "Top"),
                enumProp("horizontalAlignment", H_ALIGN, default = "Start"),
            ),
        ) {
            Node(id = NodeId.random(), type = "compose.foundation.lazy.LazyColumn")
        },
        Spec(
            "compose.foundation.lazy.LazyRow",
            "LazyRow",
            CATEGORY_LAYOUT,
            acceptsChildren = true,
            slots = emptyList(),
            props = listOf(
                enumProp("horizontalArrangement", H_ARRANGE, default = "Start"),
                enumProp("verticalAlignment", V_ALIGN, default = "Top"),
            ),
        ) {
            Node(id = NodeId.random(), type = "compose.foundation.lazy.LazyRow")
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
        Spec(
            "compose.foundation.Image",
            "Image",
            CATEGORY_CONTENT,
            acceptsChildren = false,
            slots = emptyList(),
            // `source` is a ResourceRef picked from the project's assets (PropType.Resource); a fresh
            // Image has none, so it draws a "Missing image" placeholder until one is assigned.
            props = listOf(
                PropDefinition("source", PropType.Resource),
                PropDefinition("contentDescription", PropType.String),
                enumProp("contentScale", CONTENT_SCALE, default = "Fit"),
            ),
        ) {
            Node(id = NodeId.random(), type = "compose.foundation.Image")
        },
        Spec(
            "compose.material3.Icon",
            "Icon",
            CATEGORY_CONTENT,
            acceptsChildren = false,
            slots = emptyList(),
            props = listOf(
                enumProp("icon", ICONS, default = "Star"),
                PropDefinition("contentDescription", PropType.String),
            ),
        ) {
            Node(
                id = NodeId.random(),
                type = "compose.material3.Icon",
                props = mapOf("icon" to stringLiteral("Favorite")),
            )
        },
        Spec(
            "compose.material3.Card",
            "Card",
            CATEGORY_LAYOUT,
            acceptsChildren = true,
            slots = emptyList(),
        ) {
            Node(id = NodeId.random(), type = "compose.material3.Card")
        },
        Spec(
            "compose.material3.Surface",
            "Surface",
            CATEGORY_LAYOUT,
            acceptsChildren = true,
            slots = emptyList(),
        ) {
            Node(id = NodeId.random(), type = "compose.material3.Surface")
        },
        Spec(
            "compose.material3.HorizontalDivider",
            "Divider",
            CATEGORY_CONTENT,
            acceptsChildren = false,
            slots = emptyList(),
            props = listOf(PropDefinition("thickness", PropType.Dp, default = intLiteral(1))),
        ) {
            Node(id = NodeId.random(), type = "compose.material3.HorizontalDivider")
        },
        Spec(
            "compose.material3.Checkbox",
            "Checkbox",
            CATEGORY_INPUT,
            acceptsChildren = false,
            slots = emptyList(),
            // onCheckedChange is an expression prop — never evaluated on the canvas (PF-4); edited via the
            // raw-expression hatch, exactly like Button.onClick.
            props = listOf(
                PropDefinition("checked", PropType.Bool, default = boolLiteral(false)),
                PropDefinition("enabled", PropType.Bool, default = boolLiteral(true)),
                PropDefinition(
                    "onCheckedChange",
                    PropType.String,
                    default = PropValue.RawExpression("{}"),
                    advanced = true,
                ),
            ),
        ) {
            Node(
                id = NodeId.random(),
                type = "compose.material3.Checkbox",
                props = mapOf(
                    "checked" to boolLiteral(false),
                    "onCheckedChange" to PropValue.RawExpression("{ checked -> }"),
                ),
            )
        },
        Spec(
            "compose.material3.Switch",
            "Switch",
            CATEGORY_INPUT,
            acceptsChildren = false,
            slots = emptyList(),
            props = listOf(
                PropDefinition("checked", PropType.Bool, default = boolLiteral(false)),
                PropDefinition("enabled", PropType.Bool, default = boolLiteral(true)),
                PropDefinition(
                    "onCheckedChange",
                    PropType.String,
                    default = PropValue.RawExpression("{}"),
                    advanced = true,
                ),
            ),
        ) {
            Node(
                id = NodeId.random(),
                type = "compose.material3.Switch",
                props = mapOf(
                    "checked" to boolLiteral(false),
                    "onCheckedChange" to PropValue.RawExpression("{ checked -> }"),
                ),
            )
        },
        Spec(
            "compose.material3.OutlinedButton",
            "Outlined Button",
            CATEGORY_INPUT,
            acceptsChildren = false,
            slots = listOf("content"),
            props = listOf(
                PropDefinition("onClick", PropType.String, default = PropValue.RawExpression("{}"), advanced = true),
            ),
        ) {
            buttonNode("compose.material3.OutlinedButton")
        },
        Spec(
            "compose.material3.TextButton",
            "Text Button",
            CATEGORY_INPUT,
            acceptsChildren = false,
            slots = listOf("content"),
            props = listOf(
                PropDefinition("onClick", PropType.String, default = PropValue.RawExpression("{}"), advanced = true),
            ),
        ) {
            buttonNode("compose.material3.TextButton")
        },
        Spec(
            "compose.material3.Slider",
            "Slider",
            CATEGORY_INPUT,
            acceptsChildren = false,
            slots = emptyList(),
            // onValueChange is an expression prop — never evaluated on the canvas (PF-4), like Button.onClick.
            props = listOf(
                PropDefinition("value", PropType.Float, default = floatLiteral(0f)),
                PropDefinition("enabled", PropType.Bool, default = boolLiteral(true)),
                PropDefinition(
                    "onValueChange",
                    PropType.String,
                    default = PropValue.RawExpression("{}"),
                    advanced = true,
                ),
            ),
        ) {
            Node(
                id = NodeId.random(),
                type = "compose.material3.Slider",
                props = mapOf(
                    "value" to floatLiteral(0f),
                    "onValueChange" to PropValue.RawExpression("{ value -> }"),
                ),
            )
        },
        Spec(
            "compose.material3.TextField",
            "TextField",
            CATEGORY_INPUT,
            acceptsChildren = false,
            slots = emptyList(),
            // onValueChange is an expression prop — never evaluated on the canvas (PF-4), like Button.onClick.
            // label/placeholder are @Composable slots, deferred.
            props = listOf(
                PropDefinition("value", PropType.String, default = stringLiteral("")),
                PropDefinition("enabled", PropType.Bool, default = boolLiteral(true)),
                PropDefinition(
                    "onValueChange",
                    PropType.String,
                    default = PropValue.RawExpression("{}"),
                    advanced = true,
                ),
            ),
        ) {
            textFieldNode("compose.material3.TextField")
        },
        Spec(
            "compose.material3.OutlinedTextField",
            "Outlined TextField",
            CATEGORY_INPUT,
            acceptsChildren = false,
            slots = emptyList(),
            props = listOf(
                PropDefinition("value", PropType.String, default = stringLiteral("")),
                PropDefinition("enabled", PropType.Bool, default = boolLiteral(true)),
                PropDefinition(
                    "onValueChange",
                    PropType.String,
                    default = PropValue.RawExpression("{}"),
                    advanced = true,
                ),
            ),
        ) {
            textFieldNode("compose.material3.OutlinedTextField")
        },
        Spec(
            "compose.material3.CircularProgressIndicator",
            "Circular Progress",
            CATEGORY_CONTENT,
            acceptsChildren = false,
            slots = emptyList(),
        ) {
            Node(id = NodeId.random(), type = "compose.material3.CircularProgressIndicator")
        },
        Spec(
            "compose.material3.LinearProgressIndicator",
            "Linear Progress",
            CATEGORY_CONTENT,
            acceptsChildren = false,
            slots = emptyList(),
        ) {
            Node(id = NodeId.random(), type = "compose.material3.LinearProgressIndicator")
        },
    )

    private val byType: Map<String, Spec> = specs.associateBy { it.type }

    fun specFor(type: String): Spec? = byType[type]

    private fun stringLiteral(value: String): PropValue = PropValue.Literal(JsonPrimitive(value))

    private fun intLiteral(value: Int): PropValue = PropValue.Literal(JsonPrimitive(value))

    private fun boolLiteral(value: Boolean): PropValue = PropValue.Literal(JsonPrimitive(value))

    private fun floatLiteral(value: Float): PropValue = PropValue.Literal(JsonPrimitive(value))

    /** A fresh text-field node: an empty `value` and a placeholder `onValueChange`. */
    private fun textFieldNode(type: String): Node = Node(
        id = NodeId.random(),
        type = type,
        props = mapOf(
            "value" to stringLiteral(""),
            "onValueChange" to PropValue.RawExpression("{ text -> }"),
        ),
    )

    /** A fresh button-family node: a placeholder `onClick` and a single `Text` in its content slot. */
    private fun buttonNode(type: String): Node = Node(
        id = NodeId.random(),
        type = type,
        props = mapOf("onClick" to PropValue.RawExpression("{ /* TODO */ }")),
        slots = mapOf(
            "content" to listOf(
                Node(
                    id = NodeId.random(),
                    type = "compose.material3.Text",
                    props = mapOf("text" to stringLiteral("Button")),
                ),
            ),
        ),
    )

    /** An enum prop whose literal string is one of [values] — kept in lockstep with the `Values.kt` parsers. */
    private fun enumProp(name: String, values: List<String>, default: String): PropDefinition =
        PropDefinition(name, PropType.Enum, default = stringLiteral(default), enumValues = values)

    private const val CATEGORY_LAYOUT = "Layout"
    private const val CATEGORY_CONTENT = "Content"
    private const val CATEGORY_INPUT = "Input"
}
