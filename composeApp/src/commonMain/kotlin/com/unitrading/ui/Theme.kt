package com.unitrading.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TradingColors {
    val Shell = Color(0xFF0F1B2D)
    val Surface = Color(0xFF16263B)
    val Raised = Color(0xFF1E3149)
    val Border = Color(0xFF304762)
    val Grid = Color(0xFF23364C)
    val Ink = Color(0xFFE9F0F7)
    val Muted = Color(0xFFA9B8CD)
    val Accent = Color(0xFF4C9AFF)
    val Up = Color(0xFF3987E5)
    val Down = Color(0xFFD95926)
    val Loss = Color(0xFFFF9B86)
    val Profit = Color(0xFF8CD6B2)
    val Stop = Color(0xFFECA492)
    val Target = Color(0xFF94C9B4)
}

@Composable
fun TradingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TradingColors.Accent,
            onPrimary = TradingColors.Shell,
            primaryContainer = TradingColors.Raised,
            onPrimaryContainer = TradingColors.Ink,
            secondary = TradingColors.Accent,
            secondaryContainer = TradingColors.Raised,
            onSecondaryContainer = TradingColors.Ink,
            background = TradingColors.Shell,
            surface = TradingColors.Surface,
            surfaceVariant = TradingColors.Raised,
            onSurface = TradingColors.Ink,
            onSurfaceVariant = TradingColors.Muted,
            outline = TradingColors.Border,
            error = TradingColors.Loss,
        ),
        typography = Typography(
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
            titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        ),
        shapes = Shapes(small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (subtitle != null) Text(subtitle, color = TradingColors.Muted, fontSize = 11.sp)
    }
}

@Composable
fun Badge(text: String, color: Color = TradingColors.Accent) {
    Surface(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(5.dp), border = BorderStroke(1.dp, color.copy(alpha = 0.3f))) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = TradingColors.Ink, fontSize = 10.sp)
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) = HorizontalDivider(modifier, color = TradingColors.Border.copy(alpha = 0.65f))

@Composable
fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(detail, color = TradingColors.Muted, fontSize = 12.sp)
    }
}
