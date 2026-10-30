package com.example.profilescouter.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ScouterColors = darkColorScheme(
    primary = Color(0xFF85E3C4), onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF174D40), onPrimaryContainer = Color(0xFFBBFBE5),
    secondary = Color(0xFFB3CEC4), onSecondary = Color(0xFF20372F),
    background = Color(0xFF0B1210), onBackground = Color(0xFFE1ECE6),
    surface = Color(0xFF101B17), onSurface = Color(0xFFE1ECE6),
    surfaceVariant = Color(0xFF25352E), onSurfaceVariant = Color(0xFFB5C8BE),
    outline = Color(0xFF62796D)
)

@Composable
fun ProfileScouterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ScouterColors, typography = Typography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp)), content = content)
}
