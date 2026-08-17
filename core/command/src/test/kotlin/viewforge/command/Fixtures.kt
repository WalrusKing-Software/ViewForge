package viewforge.command

import viewforge.model.ComponentDef
import viewforge.model.FrameworkRef
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.Screen

/**
 * A small, shared document for the command/history tests: one screen "s1" with a Column root holding
 * a Text and a Button whose `content` slot holds a Text. Exercises both a default region and a slot.
 */
internal object Fixtures {
    const val SCREEN = "s1"
    const val COMPONENT = "c1"

    val slotText = Node(id = NodeId("leaf"), type = "compose.material3.Text")
    val text = Node(id = NodeId("a"), type = "compose.material3.Text")
    val button = Node(
        id = NodeId("b"),
        type = "compose.material3.Button",
        slots = mapOf("content" to listOf(slotText)),
    )
    val root = Node(
        id = NodeId("root"),
        type = "compose.foundation.layout.Column",
        children = listOf(text, button),
    )

    // A reusable component with its own little tree, so a node command targeting a component root (D7
    // follow-up, ADR-027) has both a default region and two children to bite on.
    val componentText = Node(id = NodeId("c-a"), type = "compose.material3.Text")
    val componentButton = Node(id = NodeId("c-b"), type = "compose.material3.Button")
    val componentRoot = Node(
        id = NodeId("c-root"),
        type = "compose.foundation.layout.Box",
        children = listOf(componentText, componentButton),
    )

    fun project(): Project = Project(
        id = "p",
        name = "P",
        framework = FrameworkRef("compose-multiplatform", "1.0.0"),
        screens = listOf(Screen(id = SCREEN, name = "Home", root = root)),
    )

    /** [project] plus a component "c1" so edits can target a component root by id. */
    fun projectWithComponent(): Project = project().copy(
        components = listOf(ComponentDef(id = COMPONENT, name = "PrimaryButton", root = componentRoot)),
    )

    fun Project.rootOf(screenId: String = SCREEN): Node = screens.first { it.id == screenId }.root

    fun Project.componentRootOf(componentId: String = COMPONENT): Node = components.first { it.id == componentId }.root
}
