package viewforge.editor.state

import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.PropDefinition

/**
 * One selectable entry in the component palette (FEATURES P1a). Pure data — the palette UI is
 * generated from this list, so adding a component never requires palette code (CLAUDE.md
 * anti-patterns).
 */
data class PaletteEntry(val type: String, val label: String, val category: String)

/**
 * The editor's Compose-free seam onto a framework package's component set — the schema half of the
 * SPI (ARCHITECTURE §6.2), owned by the editor and *adapted* to the concrete package by `:app`, in
 * the same spirit as the `CanvasRenderer` seam (ADR-013). The editor consults it to build the palette
 * and to validate drops; it never names the Compose package.
 *
 * It intentionally exposes only what M4 needs (palette, node factory, container/slot facts). The
 * richer `PropDefinition`-driven schema the inspector will need arrives with M5, revised against real
 * use rather than guessed now (ADR-007).
 */
interface ComponentCatalog {
    /** Every component offered in the palette, in display order. */
    val palette: List<PaletteEntry>

    /** A fresh node of [type] with sensible default props/children/slots and freshly generated ids. */
    fun newNode(type: String): Node

    /** Whether [type] accepts children in its default region (a container). */
    fun acceptsChildren(type: String): Boolean

    /** The named slots [type] exposes (e.g. Button → ["content"]); empty for none. */
    fun slotsOf(type: String): List<String>

    /** Convenience: does [type] hold children in the default region or any slot at all? */
    fun isContainer(type: String): Boolean = acceptsChildren(type) || slotsOf(type).isNotEmpty()

    /** The editable prop schema for [type] — drives the data-driven inspector (M5, I1). Empty if unknown. */
    fun propsFor(type: String): List<PropDefinition>

    /** Every modifier the inspector may add, with its arg schema (the renderer-supported set). */
    val modifierCatalog: List<ModifierDefinition>

    /** The schema for one modifier [type] (for editing its args), or null if not in the catalog. */
    fun modifierDef(type: String): ModifierDefinition?
}
