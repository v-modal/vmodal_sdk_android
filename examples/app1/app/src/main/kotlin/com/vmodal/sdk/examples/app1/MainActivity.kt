package com.vmodal.sdk.examples.app1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val vm: KitchenViewModel by viewModels {
        KitchenViewModel.factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KitchenTheme {
                KitchenScreen(vm)
            }
        }
    }
}

private val primaryLight = Color(0xFF9A4418)
private val onPrimaryLight = Color(0xFFFFF5F1)
private val primaryContainerLight = Color(0xFFFFDBCD)
private val onPrimaryContainerLight = Color(0xFF341000)
private val secondaryLight = Color(0xFF2F6B4F)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFB2F1CF)
private val onSecondaryContainerLight = Color(0xFF002114)
private val surfaceLight = Color(0xFFFFF8F6)
private val backgroundLight = Color(0xFFFFF8F6)
private val onBackgroundLight = Color(0xFF231915)
private val outlineLight = Color(0xFF86736C)

private val primaryDark = Color(0xFFFFB69A)
private val onPrimaryDark = Color(0xFF5A2300)
private val primaryContainerDark = Color(0xFF7B3000)
private val onPrimaryContainerDark = Color(0xFFFFDBCD)
private val secondaryDark = Color(0xFF97D5B4)
private val onSecondaryDark = Color(0xFF003824)
private val secondaryContainerDark = Color(0xFF155038)
private val onSecondaryContainerDark = Color(0xFFB2F1CF)
private val surfaceDark = Color(0xFF1A110D)
private val backgroundDark = Color(0xFF1A110D)
private val onBackgroundDark = Color(0xFFF1DFD8)
private val outlineDark = Color(0xFFA08C85)

@Composable
private fun KitchenTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(
                primary = primaryDark,
                onPrimary = onPrimaryDark,
                primaryContainer = primaryContainerDark,
                onPrimaryContainer = onPrimaryContainerDark,
                secondary = secondaryDark,
                onSecondary = onSecondaryDark,
                secondaryContainer = secondaryContainerDark,
                onSecondaryContainer = onSecondaryContainerDark,
                surface = surfaceDark,
                background = backgroundDark,
                onBackground = onBackgroundDark,
                outline = outlineDark,
            )
        } else {
            lightColorScheme(
                primary = primaryLight,
                onPrimary = onPrimaryLight,
                primaryContainer = primaryContainerLight,
                onPrimaryContainer = onPrimaryContainerLight,
                secondary = secondaryLight,
                onSecondary = onSecondaryLight,
                secondaryContainer = secondaryContainerLight,
                onSecondaryContainer = onSecondaryContainerLight,
                surface = surfaceLight,
                background = backgroundLight,
                onBackground = onBackgroundLight,
                outline = outlineLight,
            )
        },
        content = content,
    )
}
