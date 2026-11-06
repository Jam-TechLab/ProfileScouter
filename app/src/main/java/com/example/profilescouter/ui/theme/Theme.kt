/*
 * アプリ全体で使うMaterial 3のライト配色と角丸を定義し、共通テーマを適用する。
 */
package com.example.profilescouter.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Light neutral surfaces with one restrained blue accent, shared by all screens.
private val ScouterColors = lightColorScheme(
    primary = Color(0xFF3D5F93), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3ECFA), onPrimaryContainer = Color(0xFF213F69),
    secondary = Color(0xFF526477), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EBF2), onSecondaryContainer = Color(0xFF303F50),
    tertiary = Color(0xFF596B61), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5EEE7), onTertiaryContainer = Color(0xFF35473D),
    background = Color(0xFFF7F8FA), onBackground = Color(0xFF20242C),
    surface = Color(0xFFFCFCFD), onSurface = Color(0xFF20242C),
    surfaceVariant = Color(0xFFE8EBF0), onSurfaceVariant = Color(0xFF555F70),
    surfaceDim = Color(0xFFD9DDE3), surfaceBright = Color(0xFFFCFCFD),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F6F8),
    surfaceContainer = Color(0xFFEFF1F4), surfaceContainerHigh = Color(0xFFE9ECF0),
    surfaceContainerHighest = Color(0xFFE2E6EB),
    outline = Color(0xFF747E8C), outlineVariant = Color(0xFFCAD0D9),
    inverseSurface = Color(0xFF2C313A), inverseOnSurface = Color(0xFFF2F4F8),
    inversePrimary = Color(0xFFB5CDF4), surfaceTint = Color(0xFF3D5F93),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)

@Composable
fun ProfileScouterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ScouterColors, typography = Typography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp)), content = content)
}
