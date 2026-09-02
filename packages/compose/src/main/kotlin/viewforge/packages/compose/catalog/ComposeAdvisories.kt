package viewforge.packages.compose.catalog

import kotlinx.serialization.json.intOrNull
import viewforge.model.Advisory
import viewforge.model.AdvisorySeverity
import viewforge.model.Node
import viewforge.model.PropValue

/**
 * The Compose package's non-blocking advisories for a node (#315, I8, #383). Framework-specific guidance the
 * editor surfaces in the inspector — never a codegen/load gate (that is the throwing `ProjectValidator`).
 *
 * - **Missing `contentDescription`** on an `Image`/`Icon`: a screen reader can't describe an image with no
 *   description. Warned when the prop is absent or a blank literal; an explicit description, or a binding /
 *   parameter / expression that supplies one at runtime, satisfies it.
 * - **Touch target below 48dp** on a tappable control (Button variants, Checkbox, Switch): Material's minimum
 *   interactive size. Warned when a `size`/`width`/`height` modifier pins a dimension under 48dp — the case
 *   that defeats Compose's built-in minimum-touch-target enforcement.
 * - **Responsive override without a base value** (#383): a per-breakpoint override introduces a prop not set
 *   at the Compact (base) breakpoint — codegen cannot emit the `else` branch and will refuse to export. Warned
 *   at edit time so the author can set the base value before reaching export.
 *
 * Pure and self-contained per node (it reads only the node's own props and modifiers), so it is trivially
 * unit-tested and adds no per-component UI — the inspector renders whatever list this returns.
 */
object ComposeAdvisories {
    /** The minimum interactive (touch target) size, Material accessibility guidance. */
    const val MIN_TOUCH_TARGET_DP: Int = 48

    private val NEEDS_CONTENT_DESCRIPTION = setOf(
        "compose.foundation.Image",
        "compose.material3.Icon",
    )

    private val TAPPABLE = setOf(
        "compose.material3.Button",
        "compose.material3.OutlinedButton",
        "compose.material3.TextButton",
        "compose.material3.Checkbox",
        "compose.material3.Switch",
    )

    private val SIZE_MODIFIERS = setOf("compose.size", "compose.width", "compose.height")

    fun forNode(node: Node): List<Advisory> = buildList {
        if (node.type in NEEDS_CONTENT_DESCRIPTION && contentDescriptionMissing(node)) {
            add(
                Advisory(
                    "No contentDescription — a screen reader can't describe this. Add one, or use \"\" if purely decorative.",
                    AdvisorySeverity.WARNING,
                ),
            )
        }
        if (node.type in TAPPABLE) {
            smallestConstrainedDimension(node)?.let { dp ->
                add(
                    Advisory(
                        "Touch target is ${dp}dp — below the ${MIN_TOUCH_TARGET_DP}dp minimum for a tappable control.",
                        AdvisorySeverity.WARNING,
                    ),
                )
            }
        }
        baseAbsentOverrides(node).let { bad ->
            if (bad.isNotEmpty()) {
                val propList = bad.values.flatten().distinct().sorted().joinToString()
                val bpList = bad.keys.sorted().joinToString()
                add(
                    Advisory(
                        "Responsive override(s) at [$bpList] introduce '$propList' with no base (Compact) value — " +
                            "set a base value or the export will fail.",
                        AdvisorySeverity.WARNING,
                    ),
                )
            }
        }
    }

    /** Absent, or a blank string literal. A binding/parameter/expression supplies a value at runtime, so it passes. */
    private fun contentDescriptionMissing(node: Node): Boolean {
        val value = node.props["contentDescription"] ?: return true
        return value is PropValue.Literal && value.value.content.isBlank()
    }

    /**
     * The smallest dp a `size`/`width`/`height` modifier pins the node to *below* [MIN_TOUCH_TARGET_DP], or null
     * when nothing constrains it that small. `compose.size` carries `width`+`height`; `compose.width`/`height`
     * carry a single `value`. A non-literal (expression) dimension is not evaluated (PF-4), so it is skipped.
     */
    private fun smallestConstrainedDimension(node: Node): Int? = node.modifiers
        .filter { it.type in SIZE_MODIFIERS }
        .flatMap { it.args.values }
        .mapNotNull { (it as? PropValue.Literal)?.value?.intOrNull }
        .filter { it < MIN_TOUCH_TARGET_DP }
        .minOrNull()

    /**
     * Returns a map of breakpoint-id → list of prop names that the override introduces without a base value in
     * [Node.props] (#383). An empty map means the node is clean. The codegen rejects this at export time
     * (ComponentEmitter.responsiveCall); this surfaces the same condition at edit time so the author can fix it
     * before reaching export.
     */
    private fun baseAbsentOverrides(node: Node): Map<String, List<String>> {
        if (node.responsive.isEmpty()) return emptyMap()
        val baseKeys = node.props.keys
        return node.responsive
            .mapValues { (_, overrides) -> overrides.keys.filter { it !in baseKeys } }
            .filterValues { it.isNotEmpty() }
    }
}
