package com.glassremote.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

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
    RED(Color(0xFFFF0000), "Red Dot (#FF0000)"),
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
    val accent = ThemePreferences.currentAccent.color
    return when (ThemePreferences.currentStyle) {
        AppThemeStyle.NOTHING -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(Color(0xFF000000), Color(0xFF121212), Color(0xFFFFFFFF), Color(0xFF888888), accent, Color(0xFF333333))
        } else {
            ThemeColors(Color(0xFFF0F0F0), Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF555555), accent, Color(0xFFCCCCCC))
        }
        AppThemeStyle.RETRO -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(Color(0xFF0D110D), Color(0xFF161F16), Color(0xFF33FF33), Color(0xFF1E821E), Color(0xFF33FF33), Color(0xFF1E821E))
        } else {
            ThemeColors(Color(0xFFE8F0E8), Color(0xFFD4E2D4), Color(0xFF113311), Color(0xFF446644), Color(0xFF225522), Color(0xFFAABBAA))
        }
        AppThemeStyle.E_INK -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(Color(0xFF1A1A1A), Color(0xFF262626), Color(0xFFE6E6E6), Color(0xFF999999), Color(0xFFE6E6E6), Color(0xFF404040))
        } else {
            ThemeColors(Color(0xFFFFFFFF), Color(0xFFF2F2F2), Color(0xFF1A1A1A), Color(0xFF666666), Color(0xFF1A1A1A), Color(0xFFCCCCCC))
        }
        AppThemeStyle.GLASS -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(Color(0xFF0A0A12), Color(0x1AFFFFFF), Color(0xFFFFFFFF), Color(0x88FFFFFF), accent, Color(0x33FFFFFF))
        } else {
            ThemeColors(Color(0xFFF4F5F8), Color(0x1A000000), Color(0xFF111111), Color(0x88111111), accent, Color(0x22000000))
        }
    }
}
