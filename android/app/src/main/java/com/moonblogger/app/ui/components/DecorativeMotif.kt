package com.moonblogger.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.moonblogger.app.ui.theme.MoonLavender
import com.moonblogger.app.ui.theme.MoonPink
import com.moonblogger.app.ui.theme.MoonPrimary

/** Adorno puramente visual; no añade información ni intercepta toques. */
@Composable
fun DecorativeMotif(modifier: Modifier = Modifier) {
    Canvas(modifier.size(104.dp).clearAndSetSemantics {}) {
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(MoonPink.copy(alpha = .32f), size.minDimension * .27f, center)
        drawArc(MoonPrimary.copy(alpha = .48f), 210f, 220f, false,
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawArc(MoonLavender.copy(alpha = .8f), 28f, 190f, false,
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(MoonPrimary.copy(alpha = .7f), 3.dp.toPx(), center + Offset(31.dp.toPx(), -22.dp.toPx()))
        drawCircle(MoonLavender, 4.dp.toPx(), center + Offset(-34.dp.toPx(), 24.dp.toPx()))
    }
}
