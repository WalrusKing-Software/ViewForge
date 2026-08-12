package viewforge.editor.state

import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.PropDefinition

/**
 * One selectable entry in the component palette (FEATURES P1a/P6a). Pure data — the palette UI is
 * generated from this list, so adding a component never requires palette code (CLAUDE.md
 * anti-patterns).
 *
 * [componentId] is null for a framework built-in (inserted via `catalog.newNode(type)`) and set for a
 * user-defined component (P6a): the entry's [type] is then `vforge.userComponent` and inserting it
 * builds an instance node referencing this id. It lets both kinds of entry live in one palette list and
 * one drag path.
 */
data class PaletteEntry(val type: String, val label: String, val category: String, val componentId: String? = null)

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

    /**
     * Whether [name] is a legal name for a screen in this framework — i.e. it normalizes to a valid
     * function/file identifier for the generated composable (GC-3, D6). The editor consults this for
     * **edit-time** validation feedback so a bad name fails loudly in the screen switcher rather than
     * only at export. This is a framework-specific rule (Kotlin's identifier grammar and keywords), so
     * it belongs on the seam; the concrete package delegates to its own identifier validator, keeping
     * the keyword list single-sourced.
     *
     * The default is permissive so non-validating test doubles need no override; the real adapter
     * always overrides it. Uniqueness among a document's screens is *not* a framework rule and is
     * enforced by the editor itself, not here.
     */
    fun isValidScreenName(name: String): Boolean = name.isNotBlank()
}
