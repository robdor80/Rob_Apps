package es.robertodorado.robpublisher

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val RobSuccess = Color(0xFF9ED9C2)
val RobWarning = Color(0xFFF1CF8A)
@Composable
fun RobStyle(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFEAF6F8), onPrimary = Color(0xFF020B10),
            secondary = Color(0xFF69DCFF),
            background = Color(0xFF05131A), onBackground = Color(0xFFEAF6F8),
            surface = Color(0xFF040F15), onSurface = Color(0xFFEAF6F8),
            surfaceVariant = Color(0xFF0B202B), onSurfaceVariant = Color(0xFFB8D6DB),
            outline = Color(0xFF8094AD), error = Color(0xFFFFB4AB)
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 26.sp)
        ),
        shapes = Shapes(medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(18.dp)),
        content = content
    )
}
