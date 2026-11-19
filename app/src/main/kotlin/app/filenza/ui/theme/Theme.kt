package app.filenza.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.filenza.data.Settings
import app.filenza.data.ThemeMode

/** Filenza blue, used when dynamic colour is off. */
private val LightBrand = lightColorScheme(
    primary = Color(0xFF2F5BC9), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBE1FF), onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF595E72), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE1F9), onSecondaryContainer = Color(0xFF161B2C),
    tertiary = Color(0xFF745470), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7F6), onTertiaryContainer = Color(0xFF2C122A),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAF8FF), onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFAF8FF), onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE1E2EC), onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF757780), outlineVariant = Color(0xFFC5C6D0),
    inverseSurface = Color(0xFF2F3036), inverseOnSurface = Color(0xFFF1F0F7), inversePrimary = Color(0xFFB4C5FF),
    surfaceDim = Color(0xFFDAD9E0), surfaceBright = Color(0xFFFAF8FF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF4F3FA),
    surfaceContainer = Color(0xFFEEEDF4), surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE3E1E9),
)

private val DarkBrand = darkColorScheme(
    primary = Color(0xFFB4C5FF), onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF1A41A8), onPrimaryContainer = Color(0xFFDBE1FF),
    secondary = Color(0xFFC1C5DD), onSecondary = Color(0xFF2B3042),
    secondaryContainer = Color(0xFF414659), onSecondaryContainer = Color(0xFFDDE1F9),
    tertiary = Color(0xFFE2BBDB), onTertiary = Color(0xFF432740),
    tertiaryContainer = Color(0xFF5B3D57), onTertiaryContainer = Color(0xFFFFD7F6),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121318), onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF121318), onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF44464F), onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F909A), outlineVariant = Color(0xFF44464F),
    inverseSurface = Color(0xFFE3E1E9), inverseOnSurface = Color(0xFF2F3036), inversePrimary = Color(0xFF2F5BC9),
    surfaceDim = Color(0xFF121318), surfaceBright = Color(0xFF38393F),
    surfaceContainerLowest = Color(0xFF0D0E13), surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25), surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
)

/** Colours that Material does not define: one for each file type, and status colours. */
@Immutable
data class ExtraColors(
    val success: Color,
    val warning: Color,
    val folder: Color,
    val image: Color,
    val video: Color,
    val audio: Color,
    val pdf: Color,
    val document: Color,
    val spreadsheet: Color,
    val presentation: Color,
    val archive: Color,
    val code: Color,
    val other: Color,
)

private val LightExtra = ExtraColors(
    success = Color(0xFF1B7F3B), warning = Color(0xFFB26A00), folder = Color(0xFF3F6BD8),
    image = Color(0xFF2E7D32), video = Color(0xFFC62828), audio = Color(0xFF6A1B9A), pdf = Color(0xFFD32F2F),
    document = Color(0xFF1565C0), spreadsheet = Color(0xFF2E7D32), presentation = Color(0xFFEF6C00),
    archive = Color(0xFF6D4C41), code = Color(0xFF455A64), other = Color(0xFF757575),
)

private val DarkExtra = ExtraColors(
    success = Color(0xFF7DDB94), warning = Color(0xFFFFB95C), folder = Color(0xFF9DB6FF),
    image = Color(0xFF81C784), video = Color(0xFFEF9A9A), audio = Color(0xFFCE93D8), pdf = Color(0xFFFF8A80),
    document = Color(0xFF90CAF9), spreadsheet = Color(0xFFA5D6A7), presentation = Color(0xFFFFCC80),
    archive = Color(0xFFBCAAA4), code = Color(0xFFB0BEC5), other = Color(0xFFBDBDBD),
)

private val LocalExtraColors = staticCompositionLocalOf { LightExtra }

private fun ColorScheme.pureBlack() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
)

@Composable
fun isDark(settings: Settings): Boolean = when (settings.theme) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

// The navigation bar scrims of the default enableEdgeToEdge() (internal in androidx.activity).
private val LightScrim = android.graphics.Color.argb(0xe6, 0xff, 0xff, 0xff)
private val DarkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/** Edge to edge, with bar icons for the app theme and not only for the system theme. */
fun ComponentActivity.edgeToEdge(dark: Boolean) = enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
    navigationBarStyle = SystemBarStyle.auto(LightScrim, DarkScrim) { dark },
)

/** Wallpaper colours by default, else Filenza blue; light, dark or pure black; Material 3 Expressive motion. */
@Composable
fun FilenzaTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = isDark(settings)
    val context = LocalContext.current
    val activity = LocalActivity.current as? ComponentActivity
    // A change of the app theme is not a configuration change: set the bar icons again.
    DisposableEffect(activity, dark) {
        activity?.edgeToEdge(dark)
        onDispose {}
    }
    // The configuration key reads the dynamic colours again after a wallpaper change.
    val colors = remember(dark, settings.dynamicColor, settings.pureBlack, LocalConfiguration.current) {
        val base = when {
            settings.dynamicColor && dark -> dynamicDarkColorScheme(context)
            settings.dynamicColor -> dynamicLightColorScheme(context)
            dark -> DarkBrand
            else -> LightBrand
        }
        if (dark && settings.pureBlack) base.pureBlack() else base
    }
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtra else LightExtra) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = MotionScheme.expressive(),
            typography = FilenzaTypography,
            content = content,
        )
    }
}

object AppTheme {
    val extra: ExtraColors @Composable get() = LocalExtraColors.current
    val colors: ColorScheme @Composable get() = MaterialTheme.colorScheme
}

private val FilenzaTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Medium),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Medium),
    )
}
