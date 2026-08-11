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
    val ContentScale = ClassName("androidx.compose.ui.layout", "ContentScale")

    // Theme wrapper types (M8 theme codegen)
    val ColorScheme = ClassName("androidx.compose.material3", "ColorScheme")
    val Shapes = ClassName("androidx.compose.material3", "Shapes")
    val Typography = ClassName("androidx.compose.material3", "Typography")
    val TextStyle = ClassName("androidx.compose.ui.text", "TextStyle")
    val FontWeight = ClassName("androidx.compose.ui.text.font", "FontWeight")
    val lightColorScheme = MemberName("androidx.compose.material3", "lightColorScheme")
    val darkColorScheme = MemberName("androidx.compose.material3", "darkColorScheme")
    val RoundedCornerShape = MemberName("androidx.compose.foundation.shape", "RoundedCornerShape")
    val isSystemInDarkTheme = MemberName("androidx.compose.foundation", "isSystemInDarkTheme")

    // Unit extension properties: `16.dp`, `22.sp`
    val dp = MemberName("androidx.compose.ui.unit", "dp")
    val sp = MemberName("androidx.compose.ui.unit", "sp")

    // Composables
    val Column = MemberName("androidx.compose.foundation.layout", "Column")
    val Row = MemberName("androidx.compose.foundation.layout", "Row")
    val Box = MemberName("androidx.compose.foundation.layout", "Box")
    val Spacer = MemberName("androidx.compose.foundation.layout", "Spacer")
    val LazyColumn = MemberName("androidx.compose.foundation.lazy", "LazyColumn")
    val LazyRow = MemberName("androidx.compose.foundation.lazy", "LazyRow")
    val Text = MemberName("androidx.compose.material3", "Text")
    val Button = MemberName("androidx.compose.material3", "Button")
    val OutlinedButton = MemberName("androidx.compose.material3", "OutlinedButton")
    val TextButton = MemberName("androidx.compose.material3", "TextButton")
    val Slider = MemberName("androidx.compose.material3", "Slider")
    val TextField = MemberName("androidx.compose.material3", "TextField")
    val OutlinedTextField = MemberName("androidx.compose.material3", "OutlinedTextField")
    val CircularProgressIndicator = MemberName("androidx.compose.material3", "CircularProgressIndicator")
    val LinearProgressIndicator = MemberName("androidx.compose.material3", "LinearProgressIndicator")
    val Card = MemberName("androidx.compose.material3", "Card")
    val Surface = MemberName("androidx.compose.material3", "Surface")
    val HorizontalDivider = MemberName("androidx.compose.material3", "HorizontalDivider")
    val Checkbox = MemberName("androidx.compose.material3", "Checkbox")
    val Switch = MemberName("androidx.compose.material3", "Switch")
    val Image = MemberName("androidx.compose.foundation", "Image")
    val painterResource = MemberName("androidx.compose.ui.res", "painterResource")
    val Icon = MemberName("androidx.compose.material3", "Icon")
    val TopAppBar = MemberName("androidx.compose.material3", "TopAppBar")
    val BottomAppBar = MemberName("androidx.compose.material3", "BottomAppBar")
    val Scaffold = MemberName("androidx.compose.material3", "Scaffold")

    // Opt-in plumbing for experimental Material3 APIs (TopAppBar): the generated screen function is
    // annotated `@OptIn(ExperimentalMaterial3Api::class)` when its tree uses one.
    val OptIn = ClassName("kotlin", "OptIn")
    val ExperimentalMaterial3Api = ClassName("androidx.compose.material3", "ExperimentalMaterial3Api")

    // The `Icons.Filled` receiver object; each icon is an extension property in the `.filled` package,
    // so an icon reference emits `%T.%M` — `Icons.Filled` (imports Icons) then the property (imports it).
    val IconsFilled = ClassName("androidx.compose.material.icons", "Icons", "Filled")

    fun iconMember(name: String): MemberName = MemberName("androidx.compose.material.icons.filled", name)

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
