package app.iduanju.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * B-style ground: deep blue-violet gradient with soft bokeh spots.
 * Radial-gradient brushes only — no runtime blur, so it stays cheap on Android 9 TVs.
 * Drawn by HomeScreen inside its own bounds and by RootScreen behind the root chrome
 * (rail strip) on the home route, so the whole window reads as one B-style surface.
 */
@Composable
fun BHomeBackdrop() {
    Box(Modifier.fillMaxSize().drawBehind {
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF0A0E2A), 0.45f to Color(0xFF1C1446), 0.75f to Color(0xFF120E33), 1f to Color(0xFF070818),
            ),
        )
        val spots = listOf(
            Triple(Offset(size.width * 0.06f, size.height * 0.02f), 190.dp.toPx(), Color(0xFF7B4DFF).copy(alpha = 0.20f)),
            Triple(Offset(size.width * 0.92f, size.height * 0.00f), 230.dp.toPx(), Color(0xFF3D6BFF).copy(alpha = 0.16f)),
            Triple(Offset(size.width * 0.02f, size.height * 0.55f), 150.dp.toPx(), Color(0xFFC44DFF).copy(alpha = 0.12f)),
            Triple(Offset(size.width * 0.97f, size.height * 0.78f), 260.dp.toPx(), Color(0xFF5A3DFF).copy(alpha = 0.14f)),
            Triple(Offset(size.width * 0.55f, size.height * 1.02f), 200.dp.toPx(), Color(0xFF2E9BFF).copy(alpha = 0.08f)),
            Triple(Offset(size.width * 0.30f, size.height * 0.30f), 110.dp.toPx(), Color(0xFF8A5CFF).copy(alpha = 0.08f)),
        )
        for ((center, radius, color) in spots) {
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = radius), radius = radius, center = center)
        }
    })
}
