package com.secrm.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Theme-aware colour roles. Each one reads the active Material 3 [ColorScheme], so screens follow
 * light/dark and dynamic colour without knowing about either. (The names predate the M3 scheme.)
 */
object Palette {
    val Void @Composable get() = MaterialTheme.colorScheme.background
    val Surface1 @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
    val Surface2 @Composable get() = MaterialTheme.colorScheme.surfaceContainer
    val Surface3 @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest
    val Border @Composable get() = MaterialTheme.colorScheme.outlineVariant
    val BorderStrong @Composable get() = MaterialTheme.colorScheme.outline
    val TextPrimary @Composable get() = MaterialTheme.colorScheme.onSurface
    val TextSecondary @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val TextTertiary @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val Indigo @Composable get() = MaterialTheme.colorScheme.primary
    val OnIndigo @Composable get() = MaterialTheme.colorScheme.onPrimary
    val IndigoGlow @Composable get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val Success @Composable get() = LocalStatusColors.current.success
    val Warning @Composable get() = LocalStatusColors.current.warning
    val Danger @Composable get() = MaterialTheme.colorScheme.error
}

/** Status hues Material 3 has no role for; fixed across dynamic colour so "up" is always green. */
@Immutable
class StatusColors(val success: Color, val warning: Color)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(Color(0xFF10B981), Color(0xFFF59E0B)) }

object Space {
    val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp
    val xl = 24.dp; val xxl = 32.dp; val screenPadding = 20.dp
    val cardRadius = 16.dp; val pillRadius = 999.dp
}

object Motion {
    val EmphasizedDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val Standard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    const val SnapMs = 150
    const val SweepMs = 350
    fun <T> snap() = tween<T>(SnapMs, easing = Standard)
    fun <T> sweep() = tween<T>(SweepMs, easing = EmphasizedDecel)
    fun <T> gentle() = spring<T>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
}

object SecrmType {
    val UiSans = FontFamily.Default
    val Mono = FontFamily.Monospace
    val Display = TextStyle(
        fontFamily = UiSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp
    )
    val Title = TextStyle(
        fontFamily = UiSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp
    )
    val Body = TextStyle(
        fontFamily = UiSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
    val Label = TextStyle(
        fontFamily = UiSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
    val Caption = TextStyle(
        fontFamily = UiSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
    val MonoSmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FA8FF), onPrimary = Color(0xFF0E1166),
    primaryContainer = Color(0xFF2F3396), onPrimaryContainer = Color(0xFFE0E2FF),
    secondary = Color(0xFFC1C3E8), onSecondary = Color(0xFF2A2D4D),
    secondaryContainer = Color(0xFF3F4265), onSecondaryContainer = Color(0xFFDDE0FF),
    tertiary = Color(0xFF6FD8CB), onTertiary = Color(0xFF00201D),
    tertiaryContainer = Color(0xFF0F4D47), onTertiaryContainer = Color(0xFFA0F2E8),
    error = Color(0xFFFF8A80), onError = Color(0xFF4A0A06),
    errorContainer = Color(0xFF6A1B14), onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF0D0E13), onBackground = Color(0xFFE6E6EE),
    surface = Color(0xFF0D0E13), onSurface = Color(0xFFE6E6EE),
    surfaceVariant = Color(0xFF2C2D3B), onSurfaceVariant = Color(0xFFB0B1C2),
    surfaceTint = Color(0xFF9FA8FF),
    surfaceContainerLowest = Color(0xFF08080B), surfaceContainerLow = Color(0xFF121319),
    surfaceContainer = Color(0xFF171820), surfaceContainerHigh = Color(0xFF1D1E27),
    surfaceContainerHighest = Color(0xFF242530), surfaceDim = Color(0xFF0D0E13), surfaceBright = Color(0xFF2A2B36),
    outline = Color(0xFF7C7D91), outlineVariant = Color(0xFF2E2F3D),
    inverseSurface = Color(0xFFE6E6EE), inverseOnSurface = Color(0xFF1B1B22), inversePrimary = Color(0xFF4F46E5),
    scrim = Color.Black,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E2FF), onPrimaryContainer = Color(0xFF14146B),
    secondary = Color(0xFF5B5F8A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E2FF), onSecondaryContainer = Color(0xFF181B44),
    tertiary = Color(0xFF0F766E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC6F5EE), onTertiaryContainer = Color(0xFF04302C),
    error = Color(0xFFC62828), onError = Color.White,
    errorContainer = Color(0xFFFFDAD5), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBFBFF), onBackground = Color(0xFF1A1B24),
    surface = Color(0xFFFBFBFF), onSurface = Color(0xFF1A1B24),
    surfaceVariant = Color(0xFFE3E3F0), onSurfaceVariant = Color(0xFF4A4B5C),
    surfaceTint = Color(0xFF4F46E5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F5FC),
    surfaceContainer = Color(0xFFEFEFF8), surfaceContainerHigh = Color(0xFFE9E9F3),
    surfaceContainerHighest = Color(0xFFE3E3EE), surfaceDim = Color(0xFFDADAE4), surfaceBright = Color(0xFFFBFBFF),
    outline = Color(0xFF7A7B8E), outlineVariant = Color(0xFFCACBDB),
    inverseSurface = Color(0xFF2F303A), inverseOnSurface = Color(0xFFF1F0FA), inversePrimary = Color(0xFF9FA8FF),
    scrim = Color.Black,
)

private val SecrmShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(28.dp),
)

private val SecrmTypography = Typography(
    displaySmall = SecrmType.Display, headlineSmall = SecrmType.Title.copy(fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = SecrmType.Title, titleMedium = SecrmType.Body.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = SecrmType.Body.copy(fontWeight = FontWeight.Medium),
    bodyLarge = SecrmType.Body.copy(fontSize = 16.sp, lineHeight = 22.sp), bodyMedium = SecrmType.Body,
    bodySmall = SecrmType.Label.copy(fontWeight = FontWeight.Normal),
    labelLarge = SecrmType.Body.copy(fontWeight = FontWeight.Medium), labelMedium = SecrmType.Label, labelSmall = SecrmType.Caption,
)

enum class ThemeMode { System, Light, Dark }

/**
 * Material 3 theme. [dynamicColor] takes the wallpaper palette on Android 12+, otherwise the brand
 * indigo scheme is used; [mode] forces light or dark, or follows the system.
 */
@Composable
fun SecrmTheme(
    mode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    val status = if (dark) StatusColors(Color(0xFF34D399), Color(0xFFFBBF24)) else StatusColors(Color(0xFF059669), Color(0xFFB45309))
    CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = scheme, typography = SecrmTypography, shapes = SecrmShapes, content = content)
    }
}

/** True when [SecrmTheme] with [mode] is dark, for matching the system bars. */
@Composable
fun isDark(mode: ThemeMode) = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }

/** A Material 3 card: tonal surface, large rounded shape, hairline outline and a touch of elevation. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

enum class ConnectionVisual { Idle, Connecting, Up, Warning, Error }

@Composable
fun StatusDot(state: ConnectionVisual, modifier: Modifier = Modifier) {
    val color = when (state) {
        ConnectionVisual.Idle -> Palette.TextTertiary
        ConnectionVisual.Connecting -> Palette.Warning
        ConnectionVisual.Up -> Palette.Success
        ConnectionVisual.Warning -> Palette.Warning
        ConnectionVisual.Error -> Palette.Danger
    }
    val pulse = state == ConnectionVisual.Connecting
    val t = rememberInfiniteTransition(label = "p")
    val scale by if (pulse) t.animateFloat(
        1f, 1.5f,
        infiniteRepeatable(tween(900, easing = Motion.Standard), RepeatMode.Reverse),
        label = "s"
    ) else remember { mutableStateOf(1f) }
    Box(modifier.size(8.dp).scale(scale).background(color, CircleShape))
}

fun Modifier.tappable(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

enum class PillStyle { Primary, Ghost, Danger }

@Composable
fun PillButton(
    text: String,
    style: PillStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val m = modifier.heightIn(min = 48.dp)
    when (style) {
        PillStyle.Primary -> Button(onClick, m, enabled) { Text(text) }
        PillStyle.Ghost -> FilledTonalButton(onClick, m, enabled) { Text(text) }
        PillStyle.Danger -> Button(
            onClick, m, enabled,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        ) { Text(text) }
    }
}
