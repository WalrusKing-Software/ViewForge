package viewforge.command

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

    fun project(): Project = Project(
        id = "p",
        name = "P",
        framework = FrameworkRef("compose-multiplatform", "1.0.0"),
        screens = listOf(Screen(id = SCREEN, name = "Home", root = root)),
    )

    fun Project.rootOf(screenId: String = SCREEN): Node = screens.first { it.id == screenId }.root
}
