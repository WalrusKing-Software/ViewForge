package viewforge.project

import viewforge.model.Node
import viewforge.model.Project
import viewforge.model.PropValue
import viewforge.model.UserComponent

/** Raised when a loaded project violates a structural or safety invariant (SECURITY §3). */
class ProjectValidationException(message: String) : RuntimeException(message)

/**
 * Bounds enforced when loading an untrusted `.vforge` file (PF-2). Deep nesting and huge node
 * counts crash recursive renderers and generators downstream, so they are rejected up front rather
 * than discovered the expensive way. Defaults are generous relative to any hand-built screen.
 */
data class VforgeLimits(
    val maxFileBytes: Long = 32L * 1024 * 1024,
    val maxNodes: Int = 100_000,
    val maxDepth: Int = 512,
) {
    companion object {
        val DEFAULT = VforgeLimits()
    }
}

/**
 * Structural validation for a decoded [Project]. Applied on every load (PF-2/PF-3/PF-5). Pure and
 * side-effect free — throws [ProjectValidationException] with a specific message on the first
 * violation.
 */
object ProjectValidator {
    fun validate(project: Project, limits: VforgeLimits = VforgeLimits.DEFAULT) {
        var nodeCount = 0
        val roots = project.screens.map { it.root } + project.components.map { it.root }
        for (root in roots) {
            nodeCount += measure(root, depth = 1, limits = limits, runningTotal = nodeCount)
        }
        validateAssetPaths(project)
        detectComponentCycles(project)
    }

    /** Walks a subtree, enforcing depth and cumulative node-count limits; returns nodes counted. */
    private fun measure(node: Node, depth: Int, limits: VforgeLimits, runningTotal: Int): Int {
        if (depth > limits.maxDepth) {
            throw ProjectValidationException("Tree depth exceeds limit of ${limits.maxDepth}")
        }
        var count = 1
        val children = node.children + node.slots.values.flatten()
        for (child in children) {
            if (runningTotal + count > limits.maxNodes) {
                throw ProjectValidationException("Node count exceeds limit of ${limits.maxNodes}")
            }
            count += measure(child, depth + 1, limits, runningTotal + count)
        }
        return count
    }

    private fun validateAssetPaths(project: Project) {
        for (asset in project.assets) {
            val path = asset.path
            val reason = when {
                path.isBlank() -> "is blank"
                path.startsWith("/") || path.startsWith("\\") -> "is absolute"
                path.startsWith("\\\\") -> "is a UNC path"
                Regex("^[A-Za-z]:").containsMatchIn(path) -> "is drive-relative"
                path.replace('\\', '/').split('/').any { it == ".." } -> "escapes the project root via '..'"
                else -> null
            }
            if (reason != null) {
                throw ProjectValidationException("Asset '${asset.id}' path '$path' $reason (PF-5)")
            }
        }
    }

    private fun detectComponentCycles(project: Project) {
        val byId = project.components.associateBy { it.id }
        val edges: Map<String, Set<String>> =
            project.components.associate { component ->
                component.id to referencedComponentIds(component.root).filter { it in byId }.toSet()
            }

        val visiting = HashSet<String>()
        val done = HashSet<String>()

        fun dfs(id: String) {
            if (id in done) return
            if (!visiting.add(id)) {
                throw ProjectValidationException("User component '$id' contains itself (cycle) — PF-3")
            }
            edges[id].orEmpty().forEach(::dfs)
            visiting.remove(id)
            done.add(id)
        }

        project.components.forEach { dfs(it.id) }
    }

    private fun referencedComponentIds(node: Node): List<String> {
        val here =
            if (node.type == UserComponent.TYPE) {
                (node.props[UserComponent.COMPONENT_ID_PROP] as? PropValue.Literal)
                    ?.value?.content
                    ?.let { listOf(it) }
                    .orEmpty()
            } else {
                emptyList()
            }
        val descendants = (node.children + node.slots.values.flatten()).flatMap(::referencedComponentIds)
        return here + descendants
    }
}
