package dev.vtodo.tasks.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import dev.vtodo.tasks.R

data class VTodoPalette(
    val windowBg: Color,
    val cardBg: Color,
    val cardStroke: Color,
    val tonalBg: Color,
    val rowBg: Color,
    val primary: Color,
    val primaryContainer: Color,
    val onPrimary: Color,
    val onPrimaryContainer: Color,
    val teal: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val overdueBg: Color,
    val overdueText: Color,
    val dueTodayBg: Color,
    val dueTodayText: Color,
    val dueUpcomingBg: Color,
    val dueUpcomingText: Color,
    val priorityHigh: Color,
    val priorityMedium: Color,
    val priorityLow: Color
)

val LocalVTodoPalette = staticCompositionLocalOf<VTodoPalette> {
    error("VTodoPalette not provided")
}

@Composable
fun VTodoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = VTodoPalette(
        windowBg = colorResource(R.color.window_bg),
        cardBg = colorResource(R.color.surface_card_bg),
        cardStroke = colorResource(R.color.surface_card_stroke),
        tonalBg = colorResource(R.color.surface_tonal_bg),
        rowBg = colorResource(R.color.surface_row_bg),
        primary = colorResource(R.color.accent_primary),
        primaryContainer = colorResource(R.color.accent_primary_container),
        onPrimary = colorResource(R.color.accent_on_primary),
        onPrimaryContainer = colorResource(R.color.accent_on_container),
        teal = colorResource(R.color.accent_teal),
        textPrimary = colorResource(R.color.text_primary),
        textSecondary = colorResource(R.color.text_secondary),
        textMuted = colorResource(R.color.text_muted),
        overdueBg = colorResource(R.color.overdue_bg),
        overdueText = colorResource(R.color.overdue_text),
        dueTodayBg = colorResource(R.color.due_today_bg),
        dueTodayText = colorResource(R.color.due_today_text),
        dueUpcomingBg = colorResource(R.color.due_upcoming_bg),
        dueUpcomingText = colorResource(R.color.due_upcoming_text),
        priorityHigh = colorResource(R.color.priority_high),
        priorityMedium = colorResource(R.color.priority_medium),
        priorityLow = colorResource(R.color.priority_low)
    )

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.primaryContainer,
            onPrimaryContainer = palette.onPrimaryContainer,
            secondary = palette.teal,
            background = palette.windowBg,
            onBackground = palette.textPrimary,
            surface = palette.cardBg,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.tonalBg,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.cardStroke
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.primaryContainer,
            onPrimaryContainer = palette.onPrimaryContainer,
            secondary = palette.teal,
            background = palette.windowBg,
            onBackground = palette.textPrimary,
            surface = palette.cardBg,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.tonalBg,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.cardStroke
        )
    }

    CompositionLocalProvider(LocalVTodoPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

fun parseHexColorSafe(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(fallback)
}
