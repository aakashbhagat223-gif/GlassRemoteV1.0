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
    RED(Color(0xFFFF3333), "Red Dot"),
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
                background = Color(0xFF000000), // Deep Nothing Black
                surface = Color(0xFF141414),
                textPrimary = Color(0xFFFFFFFF),
                textSecondary = Color(0xFF8A8A8A),
                accent = userAccent,
                border = Color(0xFF2C2C2C)
            )
        } else {
            ThemeColors(
                background = Color(0xFFF2F2F5),
                surface = Color(0xFFFFFFFF),
                textPrimary = Color(0xFF111111),
                textSecondary = Color(0xFF5E5E6E),
                accent = userAccent,
                border = Color(0xFFD6D6DC)
            )
        }
        AppThemeStyle.RETRO -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(
                background = Color(0xFF0B120B), // Vintage hardware dark chassis
                surface = Color(0xFF131F13),
                textPrimary = Color(0xFF33FF33),  // Phosphor green
                textSecondary = Color(0xFF1E821E),
                accent = Color(0xFF33FF33),
                border = Color(0xFF1E821E)
            )
        } else {
            ThemeColors(
                background = Color(0xFFE2EAD9), // Vintage beige plastic case
                surface = Color(0xFFCCDCC0),
                textPrimary = Color(0xFF102810),
                textSecondary = Color(0xFF3B6B3B),
                accent = Color(0xFF1E6E1E),
                border = Color(0xFF98B28E)
            )
        }
        AppThemeStyle.E_INK -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(
                background = Color(0xFF181A1D), // E-reader slate dark
                surface = Color(0xFF22262B),
                textPrimary = Color(0xFFEFEFF1),
                textSecondary = Color(0xFF9BA1A6),
                accent = Color(0xFFEFEFF1),
                border = Color(0xFF3A4046)
            )
        } else {
            ThemeColors(
                background = Color(0xFFFBFBFA), // Warm paper white
                surface = Color(0xFFFFFFFF),
                textPrimary = Color(0xFF1A1C1E),  // Deep ink black
                textSecondary = Color(0xFF5A6066),
                accent = Color(0xFF1A1C1E),
                border = Color(0xFFD8DCDE)
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
            ThemeColors(Color(0xFF000000), Color(0xFF141414), Color(0xFFFFFFFF), Color(0xFF8A8A8A), ThemePreferences.currentAccent.color, Color(0xFF2C2C2C))
        } else {
            ThemeColors(Color(0xFFF2F2F5), Color(0xFFFFFFFF), Color(0xFF111111), Color(0xFF5E5E6E), ThemePreferences.currentAccent.color, Color(0xFFD6D6DC))
        }
        AppThemeStyle.RETRO -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(Color(0xFF0B120B), Color(0xFF131F13), Color(0xFF33FF33), Color(0xFF1E821E), Color(0xFF33FF33), Color(0xFF1E821E))
        } else {
            ThemeColors(Color(0xFFE2EAD9), Color(0xFFCCDCC0), Color(0xFF102810), Color(0xFF3B6B3B), Color(0xFF1E6E1E), Color(0xFF98B28E))
        }
        AppThemeStyle.E_INK -> if (ThemePreferences.currentMode == AppThemeMode.DARK) {
            ThemeColors(Color(0xFF181A1D), Color(0xFF22262B), Color(0xFFEFEFF1), Color(0xFF9BA1A6), Color(0xFFEFEFF1), Color(0xFF3A4046))
        } else {
            ThemeColors(Color(0xFFFBFBFA), Color(0xFFFFFFFF), Color(0xFF1A1C1E), Color(0xFF5A6066), Color(0xFF1A1C1E), Color(0xFFD8DCDE))
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
