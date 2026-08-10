package viewforge.packages.compose.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName

/**
 * Every Compose symbol codegen references, as KotlinPoet [ClassName]/[MemberName] handles rather than
 * bare strings. Emitting through these is what lets KotlinPoet manage imports and guarantees the
 * structural API is used end to end (GC-1, CLAUDE.md rule 4) — there is no place a fully-qualified
 * name is pasted into source text.
 */
internal object ComposeNames {
    // Annotations / core types
    val Composable = ClassName("androidx.compose.runtime", "Composable")
    val Modifier = ClassName("androidx.compose.ui", "Modifier")
    val Alignment = ClassName("androidx.compose.ui", "Alignment")
    val Arrangement = ClassName("androidx.compose.foundation.layout", "Arrangement")
    val Color = ClassName("androidx.compose.ui.graphics", "Color")
    val MaterialTheme = ClassName("androidx.compose.material3", "MaterialTheme")

    // Unit extension property: `16.dp`
    val dp = MemberName("androidx.compose.ui.unit", "dp")

    // Composables
    val Column = MemberName("androidx.compose.foundation.layout", "Column")
    val Row = MemberName("androidx.compose.foundation.layout", "Row")
    val Box = MemberName("androidx.compose.foundation.layout", "Box")
    val Spacer = MemberName("androidx.compose.foundation.layout", "Spacer")
    val Text = MemberName("androidx.compose.material3", "Text")
    val Button = MemberName("androidx.compose.material3", "Button")

    // Modifier factories (layout live in foundation.layout; background in foundation)
    val fillMaxSize = MemberName("androidx.compose.foundation.layout", "fillMaxSize")
    val fillMaxWidth = MemberName("androidx.compose.foundation.layout", "fillMaxWidth")
    val fillMaxHeight = MemberName("androidx.compose.foundation.layout", "fillMaxHeight")
    val padding = MemberName("androidx.compose.foundation.layout", "padding")
    val size = MemberName("androidx.compose.foundation.layout", "size")
    val width = MemberName("androidx.compose.foundation.layout", "width")
    val height = MemberName("androidx.compose.foundation.layout", "height")
    val background = MemberName("androidx.compose.foundation", "background")
}
