package io.github.mesmerprism.rustyxr.companion.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val QuestColorScheme = lightColorScheme(
    primary = AccentRed,
    onPrimary = SurfaceWarm,
    primaryContainer = SurfaceWarmAlt,
    onPrimaryContainer = Ink,
    secondary = Ink,
    onSecondary = SurfaceWarm,
    background = Paper,
    onBackground = Ink,
    surface = SurfaceWarm,
    onSurface = Ink,
    surfaceVariant = SurfaceWarmAlt,
    onSurfaceVariant = Muted,
    outline = Line
)

private val QuestShapes = Shapes(
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(2.dp)
)

@Composable
fun RustyXrCompanionTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = QuestColorScheme,
        typography = RustyXrTypography,
        shapes = QuestShapes,
        content = content
    )
}

