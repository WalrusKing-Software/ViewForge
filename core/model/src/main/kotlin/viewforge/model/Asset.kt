package viewforge.model

import kotlinx.serialization.Serializable

/**
 * A reference to an imported asset (DATA_MODEL §9). [path] is ALWAYS project-relative and
 * normalized — anything escaping the project root (absolute, "../", UNC, drive-relative) is
 * rejected on load (PF-5). Assets are copied into the project on import, never referenced in place,
 * so projects stay portable and leak no author directory structure (PR-4).
 */
@Serializable
data class Asset(
    val id: String,
    val type: String,
    val path: String,
    val originalName: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)
