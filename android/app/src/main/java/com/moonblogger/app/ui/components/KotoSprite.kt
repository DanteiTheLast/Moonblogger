package com.moonblogger.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun Path.moveTo(point: Offset) = moveTo(point.x, point.y)
private fun Path.lineTo(point: Offset) = lineTo(point.x, point.y)

enum class KotoPose { Sitting, Sleeping }
enum class KotoSize(val dp: Dp) { Small(56.dp), Medium(88.dp), Large(128.dp) }

/** Pixel-pastel Koto, decorative only. */
@Composable
fun KotoSprite(pose: KotoPose = KotoPose.Sitting, size: KotoSize = KotoSize.Medium, modifier: Modifier = Modifier) {
    // A deliberately slow, tiny breath keeps Koto alive without competing with content.
    // Android's animator scale is respected: scale 0 is a still frame (useful for reduced motion).
    val transition = rememberInfiniteTransition(label = "koto-breath")
    val breath by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "koto-breath-offset",
    )
    val motionEnabled = LocalView.current.scaleX != 0f // avoid animation in non-rendering previews
    Canvas(modifier.size(size.dp).semantics { hideFromAccessibility() }) {
        drawKoto(pose, if (motionEnabled) breath else 0f)
    }
}

private fun DrawScope.drawKoto(pose: KotoPose, breath: Float) {
    val s = size.minDimension / 100f
    val bob = if (pose == KotoPose.Sleeping) 0f else breath * 1.2f
    fun p(x: Number, y: Number) = Offset(x.toFloat() * s, y.toFloat() * s)
    val outline = Color(0xFF584B61); val fur = Color(0xFFFFF8EC); val tabby = Color(0xFF8E8B99)
    val eye = Color(0xFF8A623E); val pink = Color(0xFFE78FA5)
    // Chunky pixel silhouette: compact body, oversized round head and readable features.
    drawRoundRect(outline, topLeft = p(23, 48+bob), size = androidx.compose.ui.geometry.Size(54*s, 42*s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(18*s))
    drawRoundRect(fur, topLeft = p(27, 52+bob), size = androidx.compose.ui.geometry.Size(46*s, 34*s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(15*s))
    drawCircle(outline, radius = 34*s, center = p(50, 38+bob)); drawCircle(fur, radius = 30*s, center = p(50, 38+bob))
    drawCircle(tabby, radius = 19*s, center = p(50, 29+bob)); drawRect(fur, topLeft = p(46, 18+bob), size = androidx.compose.ui.geometry.Size(8*s, 28*s))
    drawCircle(eye, 4*s, p(40, 40+bob)); drawCircle(eye, 4*s, p(60, 40+bob)); drawCircle(Color.White, 1.2f*s, p(41, 39+bob)); drawCircle(Color.White, 1.2f*s, p(61, 39+bob))
    drawCircle(pink, 3*s, p(50, 49+bob)); drawLine(outline, p(50,52+bob), p(50,55+bob), 1.5f*s)
    if (pose == KotoPose.Sleeping) { drawLine(color = outline, start = p(38,38), end = p(44,38), strokeWidth = 1.5f*s); drawLine(color = outline, start = p(56,38), end = p(62,38), strokeWidth = 1.5f*s) }
    else { drawRoundRect(tabby, topLeft = p(43,64+bob), size = androidx.compose.ui.geometry.Size(14*s, 24*s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5*s)) }
}
