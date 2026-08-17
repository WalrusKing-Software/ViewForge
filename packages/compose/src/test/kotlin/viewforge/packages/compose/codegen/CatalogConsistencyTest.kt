package viewforge.packages.compose.codegen

import viewforge.model.PropType
import viewforge.model.PropValue
import viewforge.packages.compose.catalog.ComposeComponents
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the render/catalog/codegen marriage against drift (TECHNICAL_NOTES §2, ADR-015): every enum
 * value the catalog advertises must emit as its own `Enum.Value` member, not silently collapse to a
 * fallback. If someone adds an alignment/arrangement value to [ComposeComponents] without teaching
 * the shared parser (and thus codegen), the emitted member won't match the value and this fails —
 * before the canvas and generated code can disagree.
 */
class CatalogConsistencyTest {
    @Test
    fun `every catalog enum value emits its own member`() {
        val enumProps = ComposeComponents.specs
            .flatMap { spec -> spec.props }
            .filter { it.type == PropType.Enum }

        assertTrue(enumProps.isNotEmpty(), "expected the catalog to declare enum props")

        for (prop in enumProps) {
            val values = prop.enumValues ?: fail("enum prop '${prop.name}' has no enumValues")
            for (value in values) {
                val emitted = CodegenValues.enum(
                    prop.name,
                    PropValue.Literal(kotlinx.serialization.json.JsonPrimitive(value)),
                )
                    .toString()
                assertTrue(
                    emitted.endsWith(".$value"),
                    "prop '${prop.name}' value '$value' emitted as '$emitted' — parser/codegen drift",
                )
            }
        }
    }
}
