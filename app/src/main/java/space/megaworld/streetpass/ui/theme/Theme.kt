package space.megaworld.streetpass.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

private val TinyBackground = Color(0xFF101012)
private val TinySurface = Color(0xFF171719)
private val TinySurfaceHigh = Color(0xFF242427)
private val TinyText = Color(0xFFF5F5F5)
private val TinyMuted = Color(0xFFA1A1AA)
private val StreetPassAccent = Color(0xFFFFA36C)

private val TinyGlyphDark = darkColorScheme(
    primary = StreetPassAccent, onPrimary = Color(0xFF171719),
    primaryContainer = Color(0x24FFA36C), onPrimaryContainer = StreetPassAccent,
    secondary = StreetPassAccent, onSecondary = Color(0xFF171719),
    secondaryContainer = Color(0xFF303034), onSecondaryContainer = StreetPassAccent,
    tertiary = StreetPassAccent, surfaceTint = StreetPassAccent,
    background = TinyBackground, onBackground = TinyText,
    surface = TinySurface, onSurface = TinyText,
    surfaceVariant = TinySurfaceHigh, onSurfaceVariant = TinyMuted,
    surfaceDim = TinyBackground, surfaceBright = Color(0xFF303034),
    surfaceContainerLowest = Color(0xFF0C0C0E), surfaceContainerLow = TinySurface,
    surfaceContainer = Color(0xFF1C1C1F), surfaceContainerHigh = TinySurfaceHigh,
    surfaceContainerHighest = Color(0xFF2B2B2F), outlineVariant = Color(0xFF3A3A40),
    outline = Color(0xFF65656E), error = Color(0xFFFF807A),
)

@Composable
fun StreetPassTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    // StreetPass follows tinyGlyph: dark graphite surfaces and one restrained accent.
    MaterialTheme(
        colorScheme = TinyGlyphDark,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        typography = Typography(
            displaySmall = TextStyle(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
            titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
            bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        ), content = content,
    )
}
