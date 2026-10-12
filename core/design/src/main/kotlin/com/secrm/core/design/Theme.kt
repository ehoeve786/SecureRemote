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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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

object Palette {
    val Void = Color(0xFF050506)
    val Surface1 = Color(0xFF0A0A0C)
    val Surface2 = Color(0xFF111114)
    val Surface3 = Color(0xFF1A1A1F)
    val Border = Color(0x14FFFFFF)
    val BorderStrong = Color(0x24FFFFFF)
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFF9B9BA3)
    val TextTertiary = Color(0xFF6B6B73)
    val Indigo = Color(0xFF6366F1)
    val IndigoGlow = Color(0x406366F1)
    val Success = Color(0xFF10B981)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
}

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
    val Display = TextStyle(UiSans, FontWeight.SemiBold, 28.sp, 34.sp, letterSpacing = (-0.6).sp)
    val Title = TextStyle(UiSans, FontWeight.SemiBold, 20.sp, 26.sp, letterSpacing = (-0.3).sp)
    val Body = TextStyle(UiSans, FontWeight.Normal, 14.sp, 20.sp)
    val Label = TextStyle(UiSans, FontWeight.Medium, 12.sp, 16.sp)
    val Caption = TextStyle(UiSans, FontWeight.Normal, 11.sp, 14.sp)
    val MonoSmall = TextStyle(Mono, FontWeight.Normal, 11.sp, 14.sp)
}

private val Colors = darkColorScheme(
    primary = Palette.Indigo, onPrimary = Color.White,
    background = Palette.Void, onBackground = Palette.TextPrimary,
    surface = Palette.Surface1, onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.Surface2, onSurfaceVariant = Palette.TextSecondary,
    outline = Palette.Border, error = Palette.Danger, onError = Color.White
)

@Composable
fun SecrmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        typography = Typography(
            displaySmall = SecrmType.Display, titleLarge = SecrmType.Title,
            bodyMedium = SecrmType.Body, labelMedium = SecrmType.Label,
            labelSmall = SecrmType.Caption
        ),
        content = content
    )
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Space.cardRadius))
            .background(Palette.Surface1)
            .drawBehind {
                drawRect(Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.06f),
                    0.02f to Color.Transparent
                ))
            }
            .border(1.dp, Palette.Border, RoundedCornerShape(Space.cardRadius))
    ) { content() }
}

enum class ConnectionVisual { Idle, Connecting, Up, Error }

@Composable
fun StatusDot(state: ConnectionVisual, modifier: Modifier = Modifier) {
    val color = when (state) {
        ConnectionVisual.Idle -> Palette.TextTertiary
        ConnectionVisual.Connecting -> Palette.Warning
        ConnectionVisual.Up -> Palette.Success
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

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.gentle())
    this.scale(scale).clickable(interactionSource = src, indication = null, onClick = onClick)
}

enum class PillStyle { Primary, Ghost, Danger }

@Composable
fun PillButton(text: String, style: PillStyle, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val (bg, fg) = when (style) {
        PillStyle.Primary -> Palette.Indigo to Color.White
        PillStyle.Ghost -> Palette.Surface3 to Palette.TextPrimary
        PillStyle.Danger -> Palette.Danger to Color.White
    }
    Box(
        modifier.height(48.dp)
            .clip(RoundedCornerShape(Space.pillRadius))
            .background(bg)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, style = SecrmType.Body, color = fg) }
}
