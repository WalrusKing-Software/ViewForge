package viewforge.packages.compose.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The G7 formatter (ADR-018/ADR-019): strips KotlinPoet's redundant explicit `public` and nothing
 * else. These tests pin that it removes the modifier where it is one, is idempotent, and never
 * rewrites content that merely contains the word.
 */
class KotlinFormatterTest {
    @Test
    fun `strips a leading public modifier from declarations`() {
        val input = "public fun HomeScreen() {\n}\n"
        assertEquals("fun HomeScreen() {\n}\n", KotlinFormatter.format(input))
    }

    @Test
    fun `strips public on indented and non-fun declarations`() {
        val input = "    public val x = 1\n    public class Foo\n    public object Bar\n"
        assertEquals("    val x = 1\n    class Foo\n    object Bar\n", KotlinFormatter.format(input))
    }

    @Test
    fun `is idempotent`() {
        val once = KotlinFormatter.format("public fun A() {}\n")
        assertEquals(once, KotlinFormatter.format(once))
    }

    @Test
    fun `does not touch the word public inside content`() {
        // Not a leading visibility modifier: an import, a string literal, and mid-line usage.
        val input = "import a.public.Thing\nval s = \"public fun trap\"\nval publicId = 1\n"
        assertEquals(input, KotlinFormatter.format(input))
    }

    @Test
    fun `preserves trailing newline`() {
        assertEquals("fun A() {}\n", KotlinFormatter.format("public fun A() {}\n"))
    }
}
