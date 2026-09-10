package com.vmodal.sdk.examples.framebase

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat

private val FramebasePaper = Color(0xFFECE9E2)
private val FramebaseInk = Color(0xFF252C29)
private val FramebaseRust = Color(0xFFAA4F2D)
private val FramebaseSecondary = Color(0xFF706F67)
private val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans, FontWeight.Normal),
    Font(R.font.instrument_sans, FontWeight.Medium),
    Font(R.font.instrument_sans, FontWeight.SemiBold),
    Font(R.font.instrument_sans, FontWeight.Bold),
)

class MainActivity : ComponentActivity() {
    private val viewModel: FramebaseViewModel by viewModels {
        FramebaseViewModel.factory(ArchiveStore(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent { FramebaseTheme { FramebaseScreen(viewModel) } }
    }
}

@Composable
fun FramebaseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = FramebaseRust,
            onPrimary = Color.White,
            secondary = FramebaseRust,
            background = FramebasePaper,
            surface = FramebasePaper,
            onBackground = FramebaseInk,
            onSurface = FramebaseInk,
            onSurfaceVariant = FramebaseSecondary,
            error = Color(0xFF9E3D2D),
        ),
        typography = MaterialTheme.typography.copy(
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = InstrumentSans),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = InstrumentSans),
            bodySmall = MaterialTheme.typography.bodySmall.copy(fontFamily = InstrumentSans),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = InstrumentSans),
            titleMedium = MaterialTheme.typography.titleMedium.copy(fontFamily = InstrumentSans),
            headlineSmall = MaterialTheme.typography.headlineSmall.copy(fontFamily = InstrumentSans),
        ),
        content = content,
    )
}
