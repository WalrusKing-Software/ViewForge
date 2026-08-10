package viewforge.project

import kotlinx.serialization.encodeToString
import viewforge.model.Project

/**
 * Thin (de)serialization over [VforgeJson]. Kept separate from file I/O so the wire format can be
 * tested without touching disk. For loading from disk with version gating, migration, and safety
 * validation, use [ProjectStore].
 */
object ProjectCodec {
    fun encode(project: Project): String = VforgeJson.encodeToString(project)

    fun decode(text: String): Project = VforgeJson.decodeFromString(Project.serializer(), text)
}
