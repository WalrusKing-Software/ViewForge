package viewforge.packages.compose.codegen

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.UNIT
import viewforge.model.Action
import viewforge.model.ComponentDef
import viewforge.model.Node
import viewforge.model.Project
import viewforge.model.Screen
import viewforge.model.UserComponent

/**
 * Screen-to-screen navigation codegen (ADR-039, #214). A [Action.Navigate] is lowered — like every other
 * closed [Action] (ADR-035) — with the KotlinPoet structural API (GC-1/GC-2), never string-concatenated and
 * never evaluated (PF-4): the target is a screen **id string**, looked up by a generated host, not executed.
 *
 * The shape (ADR-039, rejected `androidx.navigation` — a pinned dependency the offline no-dep philosophy and
 * the `commonMain` KMP constraint both push against): a screen that navigates takes an injected
 * `onNavigate: (String) -> Unit = {}` callback ([ON_NAVIGATE]) and `Navigate(id)` becomes `onNavigate("id")`;
 * the exporter emits a tiny [appHost] `App()` that holds the current-screen id in `remember`ed state and a
 * `when` that renders the matching screen, wiring `onNavigate = { current = it }`. No dependency, pure
 * `commonMain`, identical on desktop and Android. The default `{}` keeps a navigating screen compilable and
 * runnable standalone (an unhosted navigate is an honest no-op), so the per-screen codegen is compile-gated on
 * its own while the host is a scaffold concern the exporter assembles.
 */
internal object NavHost {
    /** The injected callback parameter a navigating screen carries; `Navigate` lowers to a call on it. */
    const val ON_NAVIGATE: String = "onNavigate"

    /** The generated host file/function that switches screens — a scaffold peer of `Main.kt`, in the default package. */
    const val APP_KT: String = "App.kt"
    const val APP_FN: String = "App"

    /** The `onNavigate` parameter type, `(String) -> Unit` — String is a screen id, never an expression (PF-4). */
    val callbackType: TypeName = LambdaTypeName.get(parameters = arrayOf(STRING), returnType = UNIT)

    /**
     * Whether [root]'s tree contains any [Action.Navigate] handler — i.e. the composable must take [ON_NAVIGATE].
     * Skips hidden subtrees exactly as the emitter does (a hidden node emits nothing, so it can't navigate), and
     * walks children and slots. **Transitive through user components (#324):** a `vforge.userComponent` instance
     * carries no handler of its own but navigates when the component it references does, so the instancing
     * screen/component also gains [ON_NAVIGATE] and forwards it (`ComponentEmitter.userComponentCall`). Resolving
     * an instance to its definition needs [components]; the walk is cycle-safe via a visited set, though a valid
     * document never contains a component cycle (PF-3, [Project.reachableComponents]).
     */
    fun navigates(root: Node, components: List<ComponentDef> = emptyList()): Boolean {
        val byId = components.associateBy { it.id }
        val visited = HashSet<String>()
        var found = false
        fun walk(node: Node) {
            if (found || node.hidden) return
            if (node.handlers.values.any { actions -> actions.any { it is Action.Navigate } }) {
                found = true
                return
            }
            // An instance navigates iff its referenced component does — recurse into that definition's tree.
            UserComponent.componentIdOf(node)?.let { id ->
                if (visited.add(id)) byId[id]?.let { walk(it.root) }
            }
            node.children.forEach(::walk)
            node.slots.values.forEach { it.forEach(::walk) }
        }
        walk(root)
        return found
    }

    /** Whether any screen in [project] navigates — the trigger for emitting [appHost] and routing entry points to it. */
    fun projectNavigates(project: Project): Boolean = project.screens.any { navigates(it.root, project.components) }

    /**
     * The `App()` host source: `var current by remember { mutableStateOf(<firstScreenId>) }` and a `when (current)`
     * that renders each screen, passing `onNavigate = { current = it }` to the ones that navigate (a target-only
     * screen is called plainly — it has no way to leave, faithfully to its own handler-free IR). The `else` arm
     * falls back to the first screen. Formatted through the G7 pass like the other scaffold entry points.
     */
    fun appHost(project: Project): String {
        val screens = project.screens
        require(screens.isNotEmpty()) { "appHost requires at least one screen" }
        val startId = screens.first().id

        val body = CodeBlock.builder()
            .add("var current by %M { %M(%S) }\n", ComposeNames.remember, ComposeNames.mutableStateOf, startId)
            .beginControlFlow("when (current)")
        screens.forEach { screen -> body.addStatement("%S -> %L", screen.id, screenCall(screen, project.components)) }
        body.addStatement("else -> %L", screenCall(screens.first(), project.components))
        body.endControlFlow()

        val fn = FunSpec.builder(APP_FN)
            .addAnnotation(ComposeNames.Composable)
            .addCode(body.build())
            .build()

        val file = FileSpec.builder("", APP_FN)
            .addFileComment("Generated by ViewForge — do not edit.\n%L", sourceLine(project))
            .indent("    ")
            // The `by` delegation on `current` needs the property-delegate operators in scope (as ADR-035 screens do).
            .addImport("androidx.compose.runtime", "getValue", "setValue")
            .addFunction(fn)
            .build()
            .toString()
        return KotlinFormatter.format(file)
    }

    /** One `when` arm's screen call: `Screen(onNavigate = { current = it })`, or a plain `Screen()` if it never navigates. */
    private fun screenCall(screen: Screen, components: List<ComponentDef>): CodeBlock {
        val fn = MemberName("", KotlinIdentifiers.requireFunctionName(screen.name))
        return if (navigates(screen.root, components)) {
            CodeBlock.of("%M(%N = { current = it })", fn, ON_NAVIGATE)
        } else {
            CodeBlock.of("%M()", fn)
        }
    }

    private fun sourceLine(project: Project): String =
        "Source: ${project.name.ifBlank { "Project" }}.vforge (schema ${project.schemaVersion})"
}
