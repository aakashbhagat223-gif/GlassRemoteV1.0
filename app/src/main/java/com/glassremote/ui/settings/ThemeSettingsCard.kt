package com.glassremote.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassremote.ui.theme.AccentColorOption
import com.glassremote.ui.theme.AppThemeMode
import com.glassremote.ui.theme.AppThemeStyle
import com.glassremote.ui.theme.ThemePreferences
import com.glassremote.ui.theme.getThemeColors
import com.glassremote.ui.theme.glass

@Composable
fun ThemeSettingsCard() {
    val context = LocalContext.current
    val colors = getThemeColors()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "UI THEME & AESTHETIC",
            color = colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )

        // Theme Styles Selector
        Text(text = "Style Preset", color = colors.textSecondary, fontSize = 12.sp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppThemeStyle.values().forEach { style ->
                val selected = ThemePreferences.currentStyle == style
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.accent.copy(alpha = 0.2f) else colors.surface)
                        .clickable {
                            ThemePreferences.save(context, style, ThemePreferences.currentMode, ThemePreferences.currentAccent)
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = style.displayName,
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // Mode Selector (Dark / Light)
        Text(text = "Display Mode", color = colors.textSecondary, fontSize = 12.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppThemeMode.values().forEach { mode ->
                val selected = ThemePreferences.currentMode == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.accent.copy(alpha = 0.2f) else colors.surface)
                        .clickable {
                            ThemePreferences.save(context, ThemePreferences.currentStyle, mode, ThemePreferences.currentAccent)
                        }
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.displayName,
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // Accent Color Selector (Nothing Red, Amber, Clean White)
        Text(text = "Glyph / Accent Color", color = colors.textSecondary, fontSize = 12.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AccentColorOption.values().forEach { accentOpt ->
                val selected = ThemePreferences.currentAccent == accentOpt
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.surface else Color.Transparent)
                        .clickable {
                            ThemePreferences.save(context, ThemePreferences.currentStyle, ThemePreferences.currentMode, accentOpt)
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(accentOpt.color, CircleShape)
                    ) {
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .align(Alignment.Center)
                                    .background(colors.background, CircleShape)
                            )
                        }
                    }
                    Text(
                        text = accentOpt.label.substringBefore(" ("),
                        color = colors.textPrimary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
