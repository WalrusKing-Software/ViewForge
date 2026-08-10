package viewforge.packages.compose.catalog

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ModifierEntry
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropValue
import viewforge.model.Ulid

/**
 * The Compose framework package's component catalog: the schema half of the SPI (ARCHITECTURE §6.2)
 * as plain, Compose-free data. It lists what the package offers to the palette, how to spawn a fresh
 * instance of each, and each type's container facts (default children / named slots) for drop
 * validation.
 *
 * It deliberately mirrors **only the currently renderable set** (`RenderNode` in
 * `render/Components.kt`): Column, Row, Box, Spacer, Text, Button. The palette must never offer a node
 * the canvas can't draw — the two grow together, at M6. `:app` adapts this to the editor's
 * `ComponentCatalog` seam (ADR-013); nothing here depends on the editor or on Compose UI types.
 */
object ComposeComponents {
    /** One catalog entry: palette metadata, container facts, and a fresh-instance factory. */
    data class Spec(
        val type: String,
        val label: String,
        val category: String,
        val acceptsChildren: Boolean,
        val slots: List<String>,
        val create: () -> Node,
    )

    val specs: List<Spec> = listOf(
        Spec(
            "compose.foundation.layout.Column",
            "Column",
            CATEGORY_LAYOUT,
            acceptsChildren = true,
            slots = emptyList(),
        ) {
            Node(id = NodeId.random(), type = "compose.foundation.layout.Column")
        },
        Spec("compose.foundation.layout.Row", "Row", CATEGORY_LAYOUT, acceptsChildren = true, slots = emptyList()) {
            Node(id = NodeId.random(), type = "compose.foundation.layout.Row")
        },
        Spec("compose.foundation.layout.Box", "Box", CATEGORY_LAYOUT, acceptsChildren = true, slots = emptyList()) {
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
        Spec("compose.material3.Text", "Text", CATEGORY_CONTENT, acceptsChildren = false, slots = emptyList()) {
            Node(id = NodeId.random(), type = "compose.material3.Text", props = mapOf("text" to stringLiteral("Text")))
        },
        Spec("compose.material3.Button", "Button", CATEGORY_INPUT, acceptsChildren = false, slots = listOf("content")) {
            Node(
                id = NodeId.random(),
                type = "compose.material3.Button",
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

    private const val CATEGORY_LAYOUT = "Layout"
    private const val CATEGORY_CONTENT = "Content"
    private const val CATEGORY_INPUT = "Input"
}
