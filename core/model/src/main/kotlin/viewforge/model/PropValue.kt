package viewforge.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * A typed prop value — never a bare string (ADR-006, DATA_MODEL §6). Serialized as a closed sealed
 * hierarchy with a `kind` discriminator (set on the format's `Json`, see core/project). Closed on
 * purpose: PF-1 requires deserialization that can never instantiate an arbitrary class by name.
 */
@Serializable
sealed interface PropValue {
    /** A literal JSON scalar (string, number, boolean). */
    @Serializable
    @SerialName("literal")
    data class Literal(val value: JsonPrimitive) : PropValue

    /** Reference to a theme token, e.g. "colors.primary" (DATA_MODEL §8). */
    @Serializable
    @SerialName("theme")
    data class ThemeRef(val token: String) : PropValue

    /** Reference to an imported asset by id (DATA_MODEL §9). */
    @Serializable
    @SerialName("resource")
    data class ResourceRef(val assetId: String) : PropValue

    /**
     * Escape hatch: a literal Kotlin expression passed straight through to codegen. NEVER evaluated
     * (PF-4) — only stored, displayed, and emitted verbatim (GC-4). The canvas renders a placeholder
     * and the UI marks the node unverified.
     */
    @Serializable
    @SerialName("expression")
    data class RawExpression(val code: String) : PropValue

    /** Reserved for Phase 2+ state bindings; carried in the schema now so adding it later is not a break. */
    @Serializable
    @SerialName("binding")
    data class StateBinding(val path: String) : PropValue

    /**
     * References a parameter of the enclosing user component by name (DATA_MODEL §4, ADR-028). Only
     * meaningful inside a [ComponentDef.root]; resolved against an instance's argument props at render
     * and codegen time, never evaluated. Its presence is what forced schema v2 — a new closed-hierarchy
     * member cannot be deserialized by a v1-only build.
     */
    @Serializable
    @SerialName("param")
    data class ParamRef(val param: String) : PropValue
}
