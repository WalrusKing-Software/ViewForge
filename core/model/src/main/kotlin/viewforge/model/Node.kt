package viewforge.model

import kotlinx.serialization.Serializable

/**
 * One modifier in a node's chain (DATA_MODEL §7).
 *
 * The position of a [ModifierEntry] within [Node.modifiers] is **semantic** — Compose's `Modifier`
 * chain is non-commutative (TECHNICAL_NOTES §1, ADR-005). Order must survive every serialization,
 * copy, and undo. Never model this as a map.
 */
@Serializable
data class ModifierEntry(
    val id: String,
    val type: String,
    val args: Map<String, PropValue> = emptyMap(),
    /** Toggle off without deleting; skipped in both render and codegen when false. */
    val enabled: Boolean = true,
)

/**
 * The central IR type (DATA_MODEL §5). Immutable; mutations produce new instances via commands
 * (ARCHITECTURE §5, CLAUDE.md rule 3).
 *
 * @property type fully-qualified, package-namespaced (e.g. "compose.foundation.layout.Column").
 *   A plain string, not an enum: the package registry supplies the set at runtime and `core` must
 *   not know it (DATA_MODEL §5). Unknown types render as an explicit placeholder, never dispatched
 *   by name (PF-6).
 * @property children the default content region.
 * @property slots named child regions (e.g. a Scaffold's topBar/content) kept separate from
 *   [children] so slot identity is never encoded in child ordering.
 * @property hidden excluded from BOTH render and codegen — a "visible in editor only" state would
 *   be a fidelity lie (DATA_MODEL §5).
 * @property handlers event handlers keyed by a catalog-declared event-slot name (e.g. "onClick",
 *   "onCheckedChange"); each value is an **ordered** list of structured [Action]s (ADR-035, #277).
 *   The action list is closed and never a code expression, so no evaluator is introduced (PF-4). Empty
 *   by default and omitted from the serialized form, so a non-interactive node is byte-identical to
 *   before schema v6; populating it is what claims the bump (an older build would silently drop it).
 */
@Serializable
data class Node(
    val id: NodeId,
    val type: String,
    val name: String? = null,
    val props: Map<String, PropValue> = emptyMap(),
    val modifiers: List<ModifierEntry> = emptyList(),
    val children: List<Node> = emptyList(),
    val slots: Map<String, List<Node>> = emptyMap(),
    val locked: Boolean = false,
    val hidden: Boolean = false,
    val handlers: Map<String, List<Action>> = emptyMap(),
)
