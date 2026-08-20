package viewforge.editor.state

import viewforge.model.EventSlotDefinition
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
 *
 * [libraryId] is set for a *global* component from the cross-project library (ADR-033, #209): inserting
 * it does not reference an existing document component but **copies** the library definition into this
 * document first (`insertLibraryComponent`), so the entry is neither a built-in nor a document component —
 * it is the third palette source. A library entry has [componentId] null; the two fields are mutually
 * exclusive.
 */
data class PaletteEntry(
    val type: String,
    val label: String,
    val category: String,
    val componentId: String? = null,
    val libraryId: String? = null,
)

/**
 * A stable identity for a palette entry, used to pin favorites and track recents (P5a, #121): a built-in is
 * keyed by its [type] (stable across projects), a library component by its [libraryId] (a global ULID,
 * stable across projects), and a document user component by its [componentId] (a per-document ULID, globally
 * unique so it never collides with another project's). The one place this key is derived.
 */
val PaletteEntry.key: String get() = libraryId ?: componentId ?: type

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

    /**
     * The closed event slots [type] exposes (ADR-035, #277) — e.g. a Button → `onClick`. Drives the inspector's
     * data-driven action editor, so a component's handlers need no per-component UI (I1 anti-pattern). Empty for
     * a component with no events; defaults to empty so non-interactive test doubles need no override.
     */
    fun eventSlotsOf(type: String): List<EventSlotDefinition> = emptyList()

    /** Every modifier the inspector may add, with its arg schema (the renderer-supported set). */
    val modifierCatalog: List<ModifierDefinition>

    /**
     * The modifiers the inspector may add to a node whose parent is [parentType] (null at the root). Some
     * modifiers are only valid in a particular parent scope — e.g. Compose's `weight` is a
     * `RowScope`/`ColumnScope` extension, so it applies only to a direct child of a Row/Column. Keeping
     * this rule on the seam (not hardcoded in the inspector) preserves the data-driven inspector: adding a
     * scope-gated modifier needs no UI code (CLAUDE.md anti-patterns). Defaults to the whole
     * [modifierCatalog] so non-gating test doubles need no override; the concrete package narrows it.
     */
    fun availableModifiers(parentType: String?): List<ModifierDefinition> = modifierCatalog

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
