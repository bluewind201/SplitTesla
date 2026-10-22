@file:OptIn(ExperimentalFoundationApi::class)

package com.splittesla.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Soft neon halo around a rounded rect, drawn as a few widening translucent
 * strokes. BlurMaskFilter would look smoother but is not hardware accelerated
 * below Android 9 and minSdk is 26.
 */
fun DrawScope.glowRect(color: Color, corner: Float, strength: Float, spread: Float = 10.dp.toPx()) {
    if (strength <= 0f) return
    for (i in 1..4) {
        val grow = spread * i / 4f
        drawRoundRect(
            color = color.copy(alpha = 0.16f * strength / i),
            topLeft = Offset(-grow, -grow),
            size = Size(size.width + grow * 2, size.height + grow * 2),
            cornerRadius = CornerRadius(corner + grow),
            style = Stroke(width = spread / 2f),
        )
    }
}

/** Shrinks while pressed and springs back, like a physical key. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.94f): Modifier {
    val down by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (down) pressed else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return this.scale(s)
}

/** Card surface used across the app: dark glass with a hairline border. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color? = null,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .background(Brush.verticalGradient(listOf(Neon.surfaceHi, Neon.surface)), shape)
            .border(1.dp, accent?.copy(alpha = 0.55f) ?: Neon.stroke, shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 3.dp, height = 12.dp).background(accent ?: Neon.lime, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                Text(title, style = Caption.copy(fontFamily = null, fontSize = 13.sp, letterSpacing = 0.5.sp))
            }
        }
        content()
    }
}

/**
 * Pill button with a neon edge. Pressing shrinks it, lights the halo and gives
 * a haptic tick. [filled] is for the one main action on a panel.
 */
@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = Neon.lime,
    filled: Boolean = false,
    active: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val glow by animateFloatAsState(
        when {
            !enabled -> 0f
            down -> 1f
            active || filled -> 0.55f
            else -> 0f
        },
        tween(180),
        label = "glow",
    )
    val bg by animateColorAsState(
        when {
            filled -> accent.copy(alpha = if (enabled) 1f else 0.3f)
            active -> accent.copy(alpha = 0.18f)
            down -> accent.copy(alpha = 0.12f)
            else -> Neon.surfaceHi
        },
        tween(150),
        label = "bg",
    )
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .pressScale(source)
            .drawBehind { glowRect(accent, 14.dp.toPx(), glow) }
            .background(bg, shape)
            .border(1.dp, if (enabled) accent.copy(alpha = if (filled || active || down) 0.9f else 0.35f) else Neon.stroke, shape)
            .combinedClickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                onLongClick = onLongClick?.let { { haptic.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = when {
                !enabled -> Neon.faint
                filled -> Color.Black
                active -> accent
                else -> Neon.text
            },
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** Status pill for a BLE link: dot colour shows the state, tap toggles the link. */
@Composable
fun LinkPill(name: String, label: String, color: Color, pulsing: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .pressScale(source)
            .background(Neon.surfaceHi, shape)
            .border(1.dp, color.copy(alpha = 0.5f), shape)
            .combinedClickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (pulsing) rememberPulse() else 1f
        Box(
            Modifier
                .size(8.dp)
                .drawBehind { drawCircle(color.copy(alpha = 0.35f * alpha), radius = size.minDimension) }
                .background(color.copy(alpha = alpha), CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(name, fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp, color = Neon.text)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = Neon.muted)
    }
}

/** One labelled metric: caption on top, big value, small unit. */
@Composable
fun Metric(caption: String, value: String, unit: String = "", color: Color = Neon.text, modifier: Modifier = Modifier, size: Int = 26) {
    Column(modifier) {
        Text(caption, style = Caption)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontFamily = Orbitron, fontWeight = FontWeight.ExtraBold, fontSize = size.sp, color = color)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Neon.muted, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

/** Thin progress bar, e.g. battery level. */
@Composable
fun NeonBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "bar")
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Neon.stroke, RoundedCornerShape(3.dp))
            .drawBehind {
                val w = size.width * f
                drawRoundRect(color.copy(alpha = 0.25f), topLeft = Offset(0f, -3.dp.toPx()), size = Size(w, size.height + 6.dp.toPx()), cornerRadius = CornerRadius(6.dp.toPx()))
                drawRoundRect(color, size = Size(w, size.height), cornerRadius = CornerRadius(3.dp.toPx()))
            },
    )
}

/** 0.35 → 1 → 0.35 breathing alpha for live or warning indicators. */
@Composable
fun rememberPulse(periodMs: Int = 900): Float {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(periodMs), RepeatMode.Reverse), label = "pulse")
    return a
}
