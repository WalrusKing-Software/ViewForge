package viewforge.packages.compose.codegen

import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Screen
import viewforge.model.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** GC-3: screen names become composable function names, so illegal ones must fail loudly, not emit uncompilable code. */
class KotlinIdentifiersTest {
    @Test
    fun `accepts legal identifiers and rejects illegal ones`() {
        assertTrue(KotlinIdentifiers.isValidFunctionName("HomeScreen"))
        assertTrue(KotlinIdentifiers.isValidFunctionName("_private2"))
        assertFalse(KotlinIdentifiers.isValidFunctionName("2Screen"), "cannot start with a digit")
        assertFalse(KotlinIdentifiers.isValidFunctionName("Home Screen"), "no spaces")
        assertFalse(KotlinIdentifiers.isValidFunctionName("class"), "hard keyword")
        assertFalse(KotlinIdentifiers.isValidFunctionName(""), "blank")
    }

    @Test
    fun `generation throws on an illegal screen name`() {
        val screen = Screen(
            id = "s",
            name = "fun",
            root = Node(id = NodeId("n"), type = "compose.foundation.layout.Column"),
        )
        val ex = assertFailsWith<CodegenException> {
            ComposeCodeGenerator().generateScreen(screen, Theme(), sourceName = "P", schemaVersion = 1)
        }
        assertTrue(ex.message!!.contains("keyword"), "message should explain why: ${ex.message}")
    }

    @Test
    fun `soft keywords are legal function names`() {
        // `data` / `value` are contextual keywords — legal as identifiers.
        assertEquals("data", KotlinIdentifiers.requireFunctionName("data"))
    }
}
