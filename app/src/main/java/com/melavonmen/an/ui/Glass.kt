package com.melavonmen.an.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.PrismalGlassSurface as PrismalSurface
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer

typealias GlassState = PrismalGlassLayer

val LocalGlassState = staticCompositionLocalOf<GlassState?> { null }

@Composable
fun rememberGlassState(): GlassState = rememberPrismalGlassLayer()

fun Modifier.glassBackdrop(state: GlassState): Modifier = prismalGlassLayer(state)

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    state: GlassState? = LocalGlassState.current,
    blurRadius: Dp = 4.dp,
    refraction: Dp = 8.dp,
    dispersion: Float = 0f,
    content: @Composable () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    if (state == null) {
        Box(
            modifier = modifier
                .shadow(14.dp, shape, clip = false)
                .clip(shape)
                .background(if (dark) Color(0x661C1C1E) else Color(0x66FFFFFF))
                .border(1.dp, Color.White.copy(alpha = 0.4f), shape)
        ) { content() }
        return
    }

    val density = LocalDensity.current
    PrismalSurface(
        backdrop = state,
        modifier = modifier,
        shape = { shape },
        blurRadius = blurRadius,
        refractionHeightPx = with(density) { 16.dp.toPx() },
        refractionAmountPx = with(density) { refraction.toPx() },
        chromaticAberration = dispersion,
        tint = Color.Unspecified,
        tintAlpha = 0f,
        saturation = 1.1f,
        content = { content() },
    )
}
