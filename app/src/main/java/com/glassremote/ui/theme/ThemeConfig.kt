package com.glassremote.ui.theme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class AppThemeStyle(val displayName: String) {
    NOTHING("Nothing OS Dot-Matrix"),
    RETRO("Retro Device"),
    E_INK("Modern E-Ink"),
    GLASS("Glass Display")
}

enum class AppThemeMode(val displayName: String) {
    DARK("Dark"),
    LIGHT("Light")
}

enum class AccentColorOption(val color: Color, val label: String) {
    RED(Color(0xFFFF0000), "Red Dot"),
    AMBER(Color(0xFFFFB000), "Amber"),
    WHITE(Color(0xFFFFFFFF), "Clean White")
}

object ThemePreferences {
    private const val PREFS_NAME = "theme_preferences"
    private const val KEY_STYLE = "theme_style"
    private const val KEY_MODE = "theme_mode"
    const val KEY_ACCENT = "theme_accent"

    var currentStyle by mutableStateOf(AppThemeStyle.NOTHING)
    var currentMode by mutableStateOf(AppThemeMode.DARK)
    var currentAccent by mutableStateOf(AccentColorOption.RED)

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentStyle = runCatching { AppThemeStyle.valueOf(prefs.getString(KEY_STYLE, AppThemeStyle.NOTHING.name)!!) }.getOrDefault(AppThemeStyle.NOTHING)
        currentMode = runCatching { AppThemeMode.valueOf(prefs.getString(KEY_MODE, AppThemeMode.DARK.name)!!) }.getOrDefault(AppThemeMode.DARK)
        currentAccent = runCatching { AccentColorOption.valueOf(prefs.getString(KEY_ACCENT, AccentColorOption.RED.name)!!) }.getOrDefault(AccentColorOption.RED)
    }

    fun save(context: Context, style: AppThemeStyle, mode: AppThemeMode, accent: AccentColorOption) {
        currentStyle = style
        currentMode = mode
        currentAccent = accent
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_STYLE, style.name)
            .putString(KEY_MODE, mode.name)
            .putString(KEY_ACCENT, accent.name)
            .apply()
    }
}

data class ThemeColors(
    val background: Color,
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val border: Color
)

@Composable
fun getThemeColors(): ThemeColors {
    val userAccent = ThemePreferences.currentAccent.color
    return when (ThemePreferences.currentStyle) {
        AppThemeStyle.NOTHING -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(
                background = Color(0xFF000000),
                surface = Color(0xFF111111),
                textPrimary = Color(0xFFFFFFFF),
                textSecondary = Color(0xFF888888),
                accent = userAccent,
                border = Color(0xFF333333)
            )
        } else {
            ThemeColors(
                background = Color(0xFFF4F4F4),
                surface = Color(0xFFFFFFFF),
                textPrimary = Color(0xFF111111),
                textSecondary = Color(0xFF666666),
                accent = userAccent,
                border = Color(0xFFD0D0D0)
            )
        }
        AppThemeStyle.RETRO -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(
                background = Color(0xFF0A0F0A),
                surface = Color(0xFF141F14),
                textPrimary = Color(0xFF33FF33),
                textSecondary = Color(0xFF1E821E),
                accent = Color(0xFF33FF33),
                border = Color(0xFF1E821E)
            )
        } else {
            ThemeColors(
                background = Color(0xFFE4EDE4),
                surface = Color(0xFFD0E0D0),
                textPrimary = Color(0xFF0F260F),
                textSecondary = Color(0xFF3B6B3B),
                accent = Color(0xFF1E6E1E),
                border = Color(0xFF9BB39B)
            )
        }
        AppThemeStyle.E_INK -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(
                background = Color(0xFF121212),
                surface = Color(0xFF1F1F1F),
                textPrimary = Color(0xFFE0E0E0),
                textSecondary = Color(0xFF9E9E9E),
                accent = Color(0xFFE0E0E0),
                border = Color(0xFF424242)
            )
        } else {
            ThemeColors(
                background = Color(0xFFFFFFFF),
                surface = Color(0xFFF0F0F0),
                textPrimary = Color(0xFF111111),
                textSecondary = Color(0xFF555555),
                accent = Color(0xFF111111),
                border = Color(0xFFCCCCCC)
            )
        }
        AppThemeStyle.GLASS -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(
                background = Color(0xFF0A0A12),
                surface = Color(0x22FFFFFF),
                textPrimary = Color(0xFFFFFFFF),
                textSecondary = Color(0x88FFFFFF),
                accent = userAccent,
                border = Color(0x33FFFFFF)
            )
        } else {
            ThemeColors(
                background = Color(0xFFEEF0F6),
                surface = Color(0x22000000),
                textPrimary = Color(0xFF111111),
                textSecondary = Color(0x88111111),
                accent = userAccent,
                border = Color(0x22000000)
            )
        }
    }
}

@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    val colors = getThemeColors()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        content = content
    )
}

fun Modifier.glass(shape: RoundedCornerShape, strong: Boolean = false): Modifier {
    val colors = when (ThemePreferences.currentStyle) {
        AppThemeStyle.NOTHING -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(Color(0xFF000000), Color(0xFF121212), Color(0xFFFFFFFF), Color(0xFF888888), ThemePreferences.currentAccent.color, Color(0xFF333333))
        } else {
            ThemeColors(Color(0xFFF4F4F4), Color(0xFFFFFFFF), Color(0xFF111111), Color(0xFF666666), ThemePreferences.currentAccent.color, Color(0xFFD0D0D0))
        }
        AppThemeStyle.RETRO -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(Color(0xFF0A0F0A), Color(0xFF141F14), Color(0xFF33FF33), Color(0xFF1E821E), Color(0xFF33FF33), Color(0xFF1E821E))
        } else {
            ThemeColors(Color(0xFFE4EDE4), Color(0xFFD0E0D0), Color(0xFF0F260F), Color(0xFF3B6B3B), Color(0xFF1E6E1E), Color(0xFF9BB39B))
        }
        AppThemeStyle.E_INK -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(Color(0xFF121212), Color(0xFF1F1F1F), Color(0xFFE0E0E0), Color(0xFF9E9E9E), Color(0xFFE0E0E0), Color(0xFF424242))
        } else {
            ThemeColors(Color(0xFFFFFFFF), Color(0xFFF0F0F0), Color(0xFF111111), Color(0xFF555555), Color(0xFF111111), Color(0xFFCCCCCC))
        }
        AppThemeStyle.GLASS -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(Color(0xFF0A0A12), if (strong) Color(0x33FFFFFF) else Color(0x1AFFFFFF), Color(0xFFFFFFFF), Color(0x88FFFFFF), ThemePreferences.currentAccent.color, Color(0x33FFFFFF))
        } else {
            ThemeColors(Color(0xFFEEF0F6), if (strong) Color(0x33000000) else Color(0x1A000000), Color(0xFF111111), Color(0x88111111), ThemePreferences.currentAccent.color, Color(0x22000000))
        }
    }

    return this
        .clip(shape)
        .background(colors.surface)
        .border(if (ThemePreferences.currentStyle == AppThemeStyle.GLASS) 1.dp else 1.5.dp, colors.border, shape)
}
