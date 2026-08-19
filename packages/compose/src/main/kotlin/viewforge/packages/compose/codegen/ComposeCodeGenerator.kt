package viewforge.packages.compose.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterSpec
import viewforge.model.Asset
import viewforge.model.ComponentDef
import viewforge.model.Node
import viewforge.model.Parameter
import viewforge.model.Project
import viewforge.model.Screen
import viewforge.model.StateField
import viewforge.model.Theme
import viewforge.spi.CodeGenerator
import viewforge.spi.GeneratedFile

/**
 * The Compose framework package's [CodeGenerator] (ARCHITECTURE §6.2, §7) — the one SPI implementation
 * until Phase 5 (ADR-007). Lowers a [Project] to idiomatic Kotlin/Compose source, one `@Composable`
 * per screen, via KotlinPoet's structural API only (GC-1). No disk access: writing is the guarded
 * writer's job (CLAUDE.md rule 6).
 *
 * Each generated file carries a header naming its source (GC-5) and takes the conventional
 * `modifier: Modifier = Modifier` parameter, onto which the root node's own chain is applied so the
 * caller's modifier comes first (DATA_MODEL §12.1).
 */
class ComposeCodeGenerator : CodeGenerator {
    override fun generate(project: Project): List<GeneratedFile> {
        val sourceName = project.name.ifBlank { "Project" }
        val screens = project.screens.map { screen ->
            GeneratedFile(
                path = "${KotlinIdentifiers.requireFunctionName(screen.name)}.kt",
                content = generateScreen(
                    screen,
                    project.theme,
                    sourceName,
                    project.schemaVersion,
                    project.assets,
                    project.components,
                    screen.state,
                ),
            )
        }
        // Each user component becomes its own composable file; instances call it by name (ADR-024), so a
        // definition edit reaches every instance. Emitted after the screens — order is irrelevant to the
        // compiler, and a component-free project stays exactly one file per screen as before.
        val components = project.components.map { component ->
            GeneratedFile(
                path = "${KotlinIdentifiers.requireFunctionName(component.name)}.kt",
                content = generateComponent(
                    component,
                    project.theme,
                    sourceName,
                    project.schemaVersion,
                    project.assets,
                    project.components,
                ),
            )
        }
        return screens + components
    }

    /**
     * The project theme's `Theme.kt` source (the reusable `AppTheme` wrapper, H4/M8), or null when the
     * theme defines nothing. Kept off [generate] — which returns screen composables only, the stable
     * SPI shape the golden suite asserts on — so the theme file is assembled by the target exporter
     * alongside `Main.kt` and the scaffold (ADR-019). Loose-file export (G4) omits it: a pasted screen
     * uses the host project's own theme.
     */
    fun generateTheme(project: Project): String? =
        ThemeEmitter.generate(project.theme, project.name.ifBlank { "Project" }, project.schemaVersion)

    /** Generates the source text for a single [screen]; also the unit the golden tests assert on. */
    fun generateScreen(
        screen: Screen,
        theme: Theme,
        sourceName: String,
        schemaVersion: Int,
        assets: List<Asset> = emptyList(),
        components: List<ComponentDef> = emptyList(),
        state: List<StateField> = emptyList(),
    ): String = generateComposable(
        KotlinIdentifiers.requireFunctionName(screen.name),
        screen.root,
        theme,
        sourceName,
        schemaVersion,
        assets,
        components,
        state = state,
    )

    /**
     * Like [generateScreen] but also returns the node→source-range map for the live preview (G3, #51).
     * [GeneratedSource.code] is identical to [generateScreen]'s output — the spans are a read-only
     * side-channel produced by an instrumented pass the export path never runs.
     */
    fun generateScreenWithSpans(
        screen: Screen,
        theme: Theme,
        sourceName: String,
        schemaVersion: Int,
        assets: List<Asset> = emptyList(),
        components: List<ComponentDef> = emptyList(),
        state: List<StateField> = emptyList(),
    ): GeneratedSource = SourceSpans.strip(
        generateComposable(
            KotlinIdentifiers.requireFunctionName(screen.name),
            screen.root,
            theme,
            sourceName,
            schemaVersion,
            assets,
            components,
            state = state,
            recordSpans = true,
        ),
    )

    /**
     * Generates the source text for a single user [component] — the same `@Composable fun Name(modifier)`
     * shape as a screen (D7). Instances reference it by a call, so this one definition backs every use.
     * A component's own read-only state (ADR-034 Amendment, component-local state) is emitted exactly as a
     * screen's — its seeded stub `val`s in the body, its record `data class`es in the file — coexisting with
     * the component's [parameters][ComponentDef.parameters]: params become function arguments, state becomes
     * body locals, and both are read from the tree without collision.
     */
    fun generateComponent(
        component: ComponentDef,
        theme: Theme,
        sourceName: String,
        schemaVersion: Int,
        assets: List<Asset> = emptyList(),
        components: List<ComponentDef> = emptyList(),
    ): String = generateComposable(
        KotlinIdentifiers.requireFunctionName(component.name),
        component.root,
        theme,
        sourceName,
        schemaVersion,
        assets,
        components,
        component.parameters,
        component.state,
    )

    /**
     * Like [generateComponent] but also returns the node→source-range map for the live preview (G3, #51),
     * so the panel can highlight the selected node while a component is open for in-place editing (#69).
     * [GeneratedSource.code] is identical to [generateComponent]'s output.
     */
    fun generateComponentWithSpans(
        component: ComponentDef,
        theme: Theme,
        sourceName: String,
        schemaVersion: Int,
        assets: List<Asset> = emptyList(),
        components: List<ComponentDef> = emptyList(),
    ): GeneratedSource = SourceSpans.strip(
        generateComposable(
            KotlinIdentifiers.requireFunctionName(component.name),
            component.root,
            theme,
            sourceName,
            schemaVersion,
            assets,
            components,
            component.parameters,
            component.state,
            recordSpans = true,
        ),
    )

    /**
     * The shared lowering for a top-level composable (a screen or a user component): emit [root] under a
     * `@Composable fun [fnName](<params>, modifier: Modifier = Modifier)`, chaining the root's own
     * modifier chain onto the caller's `modifier` (DATA_MODEL §12.1). [components] lets a
     * `vforge.userComponent` instance in the tree resolve to a call. [parameters] are a user component's
     * declared parameters (empty for a screen); each becomes a typed function parameter, referenced in
     * the body via `PropValue.ParamRef` and supplied by each instance's arguments (ADR-028).
     */
    private fun generateComposable(
        fnName: String,
        root: Node,
        theme: Theme,
        sourceName: String,
        schemaVersion: Int,
        assets: List<Asset>,
        components: List<ComponentDef>,
        parameters: List<Parameter> = emptyList(),
        state: List<StateField> = emptyList(),
        recordSpans: Boolean = false,
    ): String {
        val emitter = ComponentEmitter(theme, assets, components, recordSpans)
        // A hidden root excludes the whole tree from output (DATA_MODEL §5) — an empty body.
        val body = if (root.hidden) null else emitter.emit(root, isRoot = true)
        // Read-only screen state (ADR-034): seed a runnable stub declaring one `val` per StateField, so a
        // bound prop reads it as member access. Omitted for a hidden root (no body) and for stateless
        // screens/components, keeping their output byte-identical to before.
        val stubs = if (body == null) null else StateEmitter.stubs(state)
        val function = FunSpec.builder(fnName)
            .apply {
                // Emitting the body first sets the opt-in flag for any experimental API used (TopAppBar).
                if (emitter.requiresMaterial3OptIn) {
                    addAnnotation(
                        AnnotationSpec.builder(ComposeNames.OptIn)
                            .addMember("%T::class", ComposeNames.ExperimentalMaterial3Api)
                            .build(),
                    )
                }
            }
            .addAnnotation(ComposeNames.Composable)
            .apply {
                // Component parameters precede the conventional `modifier`. Calls always use named
                // arguments, so a defaulted parameter before `modifier` is well-formed Kotlin.
                parameters.forEach { p ->
                    val spec = ParameterSpec.builder(
                        KotlinIdentifiers.requireParameterName(p.name),
                        ParameterTypes.signatureType(p.type),
                    )
                    p.default?.let { spec.defaultValue(ParameterTypes.argValue(p.type, it, theme)) }
                    addParameter(spec.build())
                }
            }
            .addParameter(
                ParameterSpec.builder("modifier", ComposeNames.Modifier)
                    .defaultValue("%T", ComposeNames.Modifier)
                    .build(),
            )
            .apply {
                if (body != null) {
                    // Stubs (if any) precede the tree so a binding's `val` is in scope where it is read.
                    if (stubs != null) addCode(stubs)
                    addCode("%L\n", body)
                }
            }
            .build()

        return FileSpec.builder("", fnName)
            // KotlinPoet converts `·` (its non-breaking-space marker, TECHNICAL_NOTES §9) to a plain
            // space anywhere in output, so the source line uses a parenthesised separator instead.
            .addFileComment(
                "Generated by ViewForge — do not edit.\n%L",
                "Source: $sourceName.vforge (schema $schemaVersion)",
            )
            .indent("    ") // 4 spaces — Kotlin convention (KotlinPoet defaults to 2).
            .addFunction(function)
            // A generated `data class` per list-of-record state type, after the composable (order is
            // irrelevant to the compiler); none for a screen without list state, so output is unchanged.
            .apply { StateEmitter.recordTypes(state).forEach { addType(it) } }
            .build()
            .toString()
    }
}
