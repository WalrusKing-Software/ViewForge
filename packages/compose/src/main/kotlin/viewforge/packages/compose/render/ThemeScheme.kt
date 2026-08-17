package viewforge.packages.compose.render

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import viewforge.model.Theme

/**
 * Builds the real Material [ColorScheme]/[Shapes] the canvas themes with from the *project* theme
 * (H1/H4). A project token whose name matches a Material slot ([MATERIAL_COLOR_SLOTS] /
 * [MATERIAL_SHAPE_SLOTS]) overrides that slot; every other slot keeps the Material default. This is
 * why editing `colors.primary` recolors even components that never named the token (a Button's
 * container), and it mirrors exactly what the generated `AppTheme` wrapper emits (`ThemeEmitter`), so
 * canvas and compiled output agree (ADR-018).
 */

/** The project color scheme for the given [dark] mode: Material defaults with theme slots overlaid. */
fun projectColorScheme(theme: Theme, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    fun slot(name: String, fallback: Color): Color {
        val pair = theme.colors[name] ?: return fallback
        val argb = parseColorArgb(if (dark) pair.dark else pair.light) ?: return fallback
        return Color(argb)
    }
    return base.copy(
        primary = slot("primary", base.primary),
        onPrimary = slot("onPrimary", base.onPrimary),
        secondary = slot("secondary", base.secondary),
        onSecondary = slot("onSecondary", base.onSecondary),
        background = slot("background", base.background),
        onBackground = slot("onBackground", base.onBackground),
        surface = slot("surface", base.surface),
        onSurface = slot("onSurface", base.onSurface),
        error = slot("error", base.error),
        onError = slot("onError", base.onError),
    )
}

/** The project shapes: Material defaults with any `shapes.small|medium|large` corner sizes overlaid. */
fun projectShapes(theme: Theme): Shapes {
    val base = Shapes()
    fun corner(name: String, fallback: CornerBasedShape): CornerBasedShape =
        theme.shapes[name]?.let { RoundedCornerShape(it.dp) } ?: fallback
    return base.copy(
        small = corner("small", base.small),
        medium = corner("medium", base.medium),
        large = corner("large", base.large),
    )
}
